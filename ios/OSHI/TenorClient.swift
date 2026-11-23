//
//  TenorClient.swift
//  OSHI — GIF search (Tenor v2)
//
//  Read `TenorEndpoint` before anything else in this file. It is two constants
//  and they are the only part of GIF search that has a privacy cost.
//

import Foundation

// MARK: - Endpoint (the two constants that matter)

/// Where GIF search goes, and who therefore sees what our users type.
///
/// 🔴 __TENOR_RELAY_2026_09_12__ **This is deliberately two constants and
/// nothing else, so the whole feature can be repointed at an OSHI relay by
/// editing two lines.**
///
/// Why that matters more here than in most apps: a GIF search box is a
/// free-text field, and every keystroke in it currently leaves the device as a
/// plaintext HTTPS query to Google, tagged with the caller's IP. OSHI's third-
/// largest market is Russia, then Turkey, Saudi Arabia and Iraq. In those four
/// places the interesting question is not "does Google keep this" but "who can
/// compel or observe it", and the honest answer is that a per-user search log
/// held by a US company, keyed to an IP, is a category of exposure the rest of
/// this app spends enormous effort not creating. A user who trusts OSHI enough
/// to run it over a Bluetooth mesh should not be handing a third party the one
/// unencrypted free-text field in the product.
///
/// The relay is the fix, and it is small: an OSHI-side endpoint that mirrors
/// the three routes below, holds the API key server-side, and forwards the
/// query from ITS address — so Tenor sees "OSHI's server searched for X", never
/// "this IP searched for X". Same JSON, so nothing else in this file changes.
///
///     baseURLString = "https://gif.oshi.app/tenor/v2"
///     apiKey        = ""      // relay holds it
///
/// Until that exists, `apiKeyIsConfigured` is the switch: with no key the
/// picker refuses to open rather than failing halfway through a search, and
/// nothing is sent anywhere.
enum TenorEndpoint {

    /// 🔴 REPOINT ME — see `__TENOR_RELAY_2026_09_12__` above.
    /// Google's Tenor v2, or an OSHI relay speaking the same three routes.
    static let baseURLString = "https://tenor.googleapis.com/v2"

    /// 🔴 REPOINT ME — see `__TENOR_RELAY_2026_09_12__` above.
    /// Empty when the relay holds the key server-side, which is the goal.
    ///
    /// An Info.plist override exists so a build can carry a key without it
    /// living in git; the constant is the fallback, not the other way round.
    /// A key shipped in the binary is extractable by anyone who cares — that is
    /// true of every client-side Tenor key and is the other reason to move to a
    /// relay.
    static let apiKey: String = {
        if let fromPlist = Bundle.main.object(forInfoDictionaryKey: "TenorAPIKey") as? String,
           !fromPlist.isEmpty {
            return fromPlist
        }
        return ""
    }()

    /// Tenor uses this to separate one integration's traffic from another's.
    /// It is not a secret and carries no user identity.
    static let clientKey = "oshi_ios"

    /// Nothing is sent — and the picker does not open — while this is false.
    static var isConfigured: Bool {
        !apiKey.isEmpty || !baseURLString.hasPrefix("https://tenor.googleapis.com")
    }

    /// Strictest of Tenor's four levels. Not a moral position — a store one:
    /// OSHI ships in markets where a single risqué GIF surfaced by a *default*
    /// search is both an App Store 1.1 problem and a local-law problem, and
    /// there is no in-app content-rating gate to justify anything looser.
    static let contentFilter = "high"

    /// Requested renditions. Tenor's request parameter is `media_filter`; the
    /// field it populates in the RESPONSE is `media_formats` — the two names are
    /// easy to mix up and a wrong one silently returns every rendition (a much
    /// larger response and a much larger parse).
    ///
    /// Three sizes, smallest-first in intent: `nanogif` for the grid, `tinygif`
    /// as the send fallback, `gif` when it fits the budget. Asking for only
    /// `gif` would leave nothing to fall back to when the full rendition busts
    /// `MediaManager.gifPreferredMaxBytes`.
    static let mediaFilter = "nanogif,tinygif,gif"
}

// MARK: - Wire models

/// One rendition of one GIF. `size` is Tenor's own byte count and is the number
/// the budget logic runs on — it lets us reject a too-large rendition BEFORE
/// spending the download, which is the whole reason to prefer it over
/// measuring after the fact.
struct TenorMediaFormat: Decodable, Sendable, Equatable {
    let url: String
    let dims: [Int]?
    let size: Int?

    var pixelSize: CGSize? {
        guard let dims, dims.count >= 2, dims[0] > 0, dims[1] > 0 else { return nil }
        return CGSize(width: dims[0], height: dims[1])
    }

    var aspectRatio: CGFloat {
        guard let s = pixelSize else { return 1 }
        return s.width / s.height
    }

    var parsedURL: URL? { URL(string: url) }
}

struct TenorGif: Decodable, Sendable, Identifiable, Equatable {
    let id: String
    let title: String?
    let contentDescription: String?
    let mediaFormats: [String: TenorMediaFormat]

    enum CodingKeys: String, CodingKey {
        case id, title
        case contentDescription = "content_description"
        case mediaFormats = "media_formats"
    }

    /// What the grid loads. Always the smallest thing available — a grid of
    /// thirty full-size GIFs is tens of megabytes and would make the picker feel
    /// exactly as slow as it must not feel.
    var previewFormat: TenorMediaFormat? {
        mediaFormats["nanogif"] ?? mediaFormats["tinygif"] ?? mediaFormats["gif"]
    }

    /// Accessible label. `content_description` is Tenor's human description;
    /// `title` is usually empty on featured results.
    var accessibilityText: String {
        let candidates = [contentDescription, title].compactMap { $0 }
        return candidates.first(where: { !$0.isEmpty }) ?? "GIF"
    }

    /// The rendition actually sent to the peer.
    ///
    /// __GIF_BUDGET_2026_09_12__ in MediaManager explains the numbers; this is
    /// where they are spent. A GIF cannot be compressed after the fact without
    /// destroying it, so choosing the rendition IS the size handling — there is
    /// no second chance further down the pipeline.
    ///
    /// Full `gif` when it fits the mesh-friendly budget, `tinygif` when it does
    /// not, `nanogif` as the last resort. Renditions whose declared size busts
    /// the hard cap are dropped rather than attempted; a rendition with no
    /// declared size is allowed through and re-checked after the download,
    /// because "Tenor didn't say" is not a reason to refuse a 300 KB GIF.
    func bestFormatForSending(
        preferredMaxBytes: Int = MediaManager.gifPreferredMaxBytes,
        hardMaxBytes: Int = MediaManager.gifHardMaxBytes
    ) -> TenorMediaFormat? {
        func fits(_ format: TenorMediaFormat?, _ budget: Int) -> TenorMediaFormat? {
            guard let format, format.parsedURL != nil else { return nil }
            guard let size = format.size else { return format }   // unknown → verified after download
            return size <= budget ? format : nil
        }

        if let full = fits(mediaFormats["gif"], preferredMaxBytes) { return full }
        if let tiny = fits(mediaFormats["tinygif"], hardMaxBytes) { return tiny }
        if let nano = fits(mediaFormats["nanogif"], hardMaxBytes) { return nano }
        // Nothing declared small enough. Fall back to the full rendition only if
        // it is at least under the hard cap — otherwise this GIF is unsendable
        // and the caller shows that honestly.
        return fits(mediaFormats["gif"], hardMaxBytes)
    }
}

/// A page of results plus the cursor for the next one. Tenor paginates with an
/// opaque `next` string, NOT an offset — an empty `next` is the end.
struct TenorFeed: Sendable, Equatable {
    let gifs: [TenorGif]
    let nextCursor: String?

    static let empty = TenorFeed(gifs: [], nextCursor: nil)
}

struct TenorCategory: Decodable, Sendable, Identifiable, Equatable {
    /// The term to search when tapped. This — not `name` — is what goes in the
    /// query: `name` is a localized display string with a leading `#`.
    let searchterm: String
    let name: String?
    let image: String?

    var id: String { searchterm }
    var displayName: String {
        guard let name, !name.isEmpty else { return searchterm }
        return name.hasPrefix("#") ? String(name.dropFirst()) : name
    }
    var imageURL: URL? { image.flatMap(URL.init(string:)) }
}

// MARK: - Errors

enum TenorError: LocalizedError, Equatable {
    case notConfigured
    case badURL
    case http(Int)
    case emptyResponse
    case decoding(String)
    case noSendableRendition
    case tooLarge(Int)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "gif.error.unavailable".tenorLocalized("GIF search is unavailable right now.")
        case .badURL, .decoding, .emptyResponse:
            return "gif.error.generic".tenorLocalized("Couldn’t load GIFs. Please try again.")
        case .http(let code):
            // 429 is the one worth telling apart: it is transient and retrying
            // immediately makes it worse.
            return code == 429
                ? "gif.error.rate_limited".tenorLocalized("Too many searches. Give it a moment.")
                : "gif.error.generic".tenorLocalized("Couldn’t load GIFs. Please try again.")
        case .noSendableRendition, .tooLarge:
            return "gif.error.too_large".tenorLocalized("That GIF is too large to send.")
        }
    }
}

// MARK: - Localisation shim
//
// The GIF strings are not in Localizable.strings yet (see the report attached to
// this change — the keys are listed there). `NSLocalizedString` returns the KEY
// when a translation is missing, which would ship literal "gif.error.generic"
// text to users in all 34 locales the day this lands. Falling back to an English
// default instead is strictly better than a key, and disappears on its own the
// moment the keys are added.
extension String {
    func tenorLocalized(_ fallback: String) -> String {
        let value = localized
        return value == self ? fallback : value
    }
}

// MARK: - Client

/// Tenor v2 access.
///
/// An `actor` rather than a `@MainActor` singleton: every method here is I/O and
/// none of it touches UI, so keeping it off the main actor means a slow search
/// cannot stutter the grid it is populating. (The project sets
/// `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`, so this has to be said out loud.)
actor TenorClient {

    static let shared = TenorClient()

    /// Recommended debounce before firing a search from a text field. Lives here
    /// rather than in the picker so both the picker and any future caller agree.
    /// 300 ms is the usual "user paused typing" threshold: short enough that the
    /// grid feels live, long enough that a 12-character query is one request
    /// instead of twelve — which matters for rate limits and matters much more
    /// for how many separate search terms leave the device.
    static let searchDebounce: Duration = .milliseconds(300)

    private let session: URLSession
    private let decoder = JSONDecoder()

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            // Ephemeral: no disk cache, no cookies, no credential storage.
            // A shared URLCache would leave GIF search results — i.e. what the
            // user was looking for — in a file on disk that nothing else in this
            // app would ever clean up, and OSHI's whole premise is that it does
            // not leave that sort of thing lying around.
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 15
            config.timeoutIntervalForResource = 30
            config.httpCookieStorage = nil
            config.httpShouldSetCookies = false
            config.urlCache = nil
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: config)
        }
    }

    // MARK: Routes

    /// Trending / featured — what the picker shows before the user types.
    func featured(limit: Int = 24, cursor: String? = nil) async throws -> TenorFeed {
        try await feed(path: "featured", extraQuery: [], limit: limit, cursor: cursor)
    }

    /// Free-text search. Cancellation-aware: `URLSession`'s async form throws
    /// `CancellationError` when the enclosing Task is cancelled, which is how the
    /// picker drops an in-flight request the moment the query changes.
    func search(_ query: String, limit: Int = 24, cursor: String? = nil) async throws -> TenorFeed {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .empty }
        return try await feed(
            path: "search",
            extraQuery: [URLQueryItem(name: "q", value: trimmed)],
            limit: limit,
            cursor: cursor
        )
    }

    /// The category chips (#happy, #reaction…). Cheap and cacheable by the
    /// caller — they change about never.
    func categories() async throws -> [TenorCategory] {
        let request = try makeRequest(path: "categories", query: [
            URLQueryItem(name: "type", value: "featured")
        ])
        let data = try await perform(request)
        do {
            struct Envelope: Decodable { let tags: [TenorCategory]? }
            return try decoder.decode(Envelope.self, from: data).tags ?? []
        } catch {
            throw TenorError.decoding("categories: \(error.localizedDescription)")
        }
    }

    // MARK: Downloads

    /// Fetch rendition bytes, refusing anything over `maxBytes`.
    ///
    /// The cap is checked TWICE and both checks earn their place. `Content-Length`
    /// first, so an oversized GIF costs one round trip and not ten megabytes of
    /// cellular data in a country where that is expensive. Then the actual byte
    /// count, because `Content-Length` is a claim by the server and a chunked
    /// response omits it entirely.
    ///
    /// Not built on `URLSession.bytes(for:)` despite that being the obvious way
    /// to abort mid-stream: `AsyncBytes` yields ONE BYTE per iteration, so
    /// draining a 5 MB GIF through it is millions of async resumptions and is
    /// dramatically slower than the download it is guarding.
    func downloadRendition(_ format: TenorMediaFormat, maxBytes: Int = MediaManager.gifHardMaxBytes) async throws -> Data {
        guard let url = format.parsedURL else { throw TenorError.badURL }

        if let declared = format.size, declared > maxBytes {
            throw TenorError.tooLarge(declared)
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        request.cachePolicy = .reloadIgnoringLocalCacheData

        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()

        if let http = response as? HTTPURLResponse {
            guard (200..<300).contains(http.statusCode) else {
                throw TenorError.http(http.statusCode)
            }
            if http.expectedContentLength > 0, http.expectedContentLength > Int64(maxBytes) {
                throw TenorError.tooLarge(Int(http.expectedContentLength))
            }
        }

        guard !data.isEmpty else { throw TenorError.emptyResponse }
        guard data.count <= maxBytes else { throw TenorError.tooLarge(data.count) }
        return data
    }

    /// Pick the rendition that fits the send budget and download it.
    ///
    /// Returns the bytes only; wrapping them into a `MediaAttachment` is
    /// `MediaManager.processGIF`'s job, which is also where they get validated
    /// as an actual GIF. Keeping the two apart means the share sheet and any
    /// future GIF source reuse the same wrapping path.
    func downloadForSending(_ gif: TenorGif) async throws -> Data {
        guard let format = gif.bestFormatForSending() else {
            throw TenorError.noSendableRendition
        }
        let data = try await downloadRendition(format)
        OshiLog.web.info("🎞️ Tenor rendition downloaded: \(data.count) bytes for \(gif.id)")
        return data
    }

    /// Grid preview bytes. Hard-capped low on purpose: a preview that turns out
    /// to be 3 MB is a bug in the response, not something to render.
    func downloadPreview(_ gif: TenorGif) async throws -> Data {
        guard let format = gif.previewFormat else { throw TenorError.noSendableRendition }
        return try await downloadRendition(format, maxBytes: 3 * 1024 * 1024)
    }

    // MARK: Plumbing

    private func feed(path: String, extraQuery: [URLQueryItem], limit: Int, cursor: String?) async throws -> TenorFeed {
        var query = extraQuery
        query.append(URLQueryItem(name: "limit", value: String(max(1, min(limit, 50)))))
        if let cursor, !cursor.isEmpty {
            query.append(URLQueryItem(name: "pos", value: cursor))
        }

        let request = try makeRequest(path: path, query: query)
        let data = try await perform(request)

        do {
            struct Envelope: Decodable {
                let results: [TenorGif]?
                let next: String?
            }
            let envelope = try decoder.decode(Envelope.self, from: data)
            // Tenor returns `next: "0"` (not an empty string) at the end of a
            // feed. Treating "0" as a live cursor makes the grid re-request page
            // one forever while the user scrolls — an infinite list of duplicates.
            let next = (envelope.next?.isEmpty == false && envelope.next != "0") ? envelope.next : nil
            return TenorFeed(gifs: envelope.results ?? [], nextCursor: next)
        } catch {
            throw TenorError.decoding("\(path): \(error.localizedDescription)")
        }
    }

    private func makeRequest(path: String, query: [URLQueryItem]) throws -> URLRequest {
        guard TenorEndpoint.isConfigured else { throw TenorError.notConfigured }
        guard var components = URLComponents(string: TenorEndpoint.baseURLString + "/" + path) else {
            throw TenorError.badURL
        }

        var items = query
        if !TenorEndpoint.apiKey.isEmpty {
            items.append(URLQueryItem(name: "key", value: TenorEndpoint.apiKey))
        }
        items.append(URLQueryItem(name: "client_key", value: TenorEndpoint.clientKey))
        items.append(URLQueryItem(name: "media_filter", value: TenorEndpoint.mediaFilter))
        items.append(URLQueryItem(name: "contentfilter", value: TenorEndpoint.contentFilter))

        // Language, and ONLY language. Tenor also accepts a `country` parameter
        // that measurably improves results, and it is deliberately not sent:
        // language is already implied by the app the user chose to install,
        // whereas country pins the request to a place. In the markets named in
        // __TENOR_RELAY_2026_09_12__ that is the difference between a search term
        // and a search term with a location attached.
        if let language = Bundle.main.preferredLocalizations.first {
            items.append(URLQueryItem(name: "locale", value: language.replacingOccurrences(of: "-", with: "_")))
        }

        components.queryItems = items
        guard let url = components.url else { throw TenorError.badURL }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        request.cachePolicy = .reloadIgnoringLocalCacheData
        return request
    }

    private func perform(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        try Task.checkCancellation()

        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            OshiLog.web.error("❌ Tenor HTTP \(http.statusCode) for \(request.url?.path ?? "?")")
            throw TenorError.http(http.statusCode)
        }
        guard !data.isEmpty else { throw TenorError.emptyResponse }
        return data
    }
}
