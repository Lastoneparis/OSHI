//
//  LinkPreview.swift
//  OSHI - WhatsApp-style Link Preview
//

import SwiftUI
import Foundation
import CryptoKit

// MARK: - Link Preview Data Model

struct LinkPreviewData: Codable, Identifiable {
    let id: UUID
    let url: String
    let title: String?
    let description: String?
    let imageURL: String?
    let siteName: String?
    let favicon: String?
    
    init(url: String, title: String? = nil, description: String? = nil, imageURL: String? = nil, siteName: String? = nil, favicon: String? = nil) {
        self.id = UUID()
        self.url = url
        self.title = title
        self.description = description
        self.imageURL = imageURL
        self.siteName = siteName
        self.favicon = favicon
    }
    
    var displayTitle: String {
        title ?? siteName ?? url
    }
    
    var displayDescription: String? {
        description
    }
}

// MARK: - Link Preview Fetcher

/// __LINK_PREVIEW_CACHE_2026_08_14__ Metadata + image, kept across scrolls AND
/// across launches.
///
/// Every LinkPreviewView owns its own fetcher, so scrolling a link back into view
/// re-ran the whole thing: an 8s-timeout HTML GET of the page, then a second GET
/// for the image. A thread with five links re-downloaded five pages every time it
/// was opened. Nothing here was cached — the word did not appear in this file.
///
/// Memory first (instant, survives scrolling), then disk (survives relaunch),
/// then the network. Disk entries expire after 7 days: a preview is a snapshot of
/// someone else's page, and a stale title is worse than a slightly slower load.
final class LinkPreviewCache {
    static let shared = LinkPreviewCache()

    private struct Entry: Codable {
        let data: LinkPreviewData
        let image: Data?
        let storedAt: Date
    }

    private let memory = NSCache<NSString, NSData>()
    private let ttl: TimeInterval = 7 * 24 * 3600
    private let dir: URL

    private init() {
        memory.countLimit = 200
        // ~24 MB: previews are small, and this is a Caches dir the OS may purge.
        memory.totalCostLimit = 24 * 1024 * 1024
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("LinkPreviews", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        purgeLegacyEntries()
    }

    /// __PLAINTEXT_LEFTOVERS_2026_09_24__ Nom de fichier = HMAC-SHA256 de l'URL
    /// sous une sous-clé de la clé au repos. Avant: base64 de l'URL — le nom
    /// SEUL rendait lisible chaque lien reçu en message, et le contenu (titre,
    /// description, image) était du JSON en clair. Clé indisponible (appareil
    /// jamais déverrouillé) → mémoire seulement.
    private let keyLock = NSLock()
    private var cachedNameKey: SymmetricKey?
    private var nameKey: SymmetricKey? {
        keyLock.lock(); defer { keyLock.unlock() }
        if cachedNameKey == nil {
            cachedNameKey = try? AtRestCipher.shared.deriveSubkey(salt: Data("oshi-link-preview".utf8),
                                                                  info: "oshi-link-preview-name-v1")
        }
        return cachedNameKey
    }

    private func fileName(_ urlString: String) -> String? {
        guard let nameKey else { return nil }
        let mac = HMAC<SHA256>.authenticationCode(for: Data(urlString.utf8), using: nameKey)
        return mac.map { String(format: "%02x", $0) }.joined()
    }

    /// Les entrées de l'ancien format (nom = URL en base64, contenu en clair) ne
    /// sont qu'un cache: supprimées, elles se referont chiffrées au besoin.
    private func purgeLegacyEntries() {
        let dir = self.dir
        DispatchQueue.global(qos: .utility).async {
            let fm = FileManager.default
            guard let items = try? fm.contentsOfDirectory(atPath: dir.path) else { return }
            for name in items where name.count != 64 || name.contains(where: { !$0.isHexDigit }) {
                try? fm.removeItem(at: dir.appendingPathComponent(name))
            }
        }
    }

    func lookup(_ urlString: String) -> (LinkPreviewData, Data?)? {
        let k = urlString
        let name = fileName(urlString)
        var blob: Data?
        if let m = memory.object(forKey: k as NSString) { blob = m as Data }
        else if let name {
            blob = (try? Data(contentsOf: dir.appendingPathComponent(name))).flatMap { try? AtRestCipher.shared.open($0) }
        }
        guard let blob, let e = try? JSONDecoder().decode(Entry.self, from: blob) else { return nil }
        guard Date().timeIntervalSince(e.storedAt) < ttl else {
            if let name { try? FileManager.default.removeItem(at: dir.appendingPathComponent(name)) }
            memory.removeObject(forKey: k as NSString)
            return nil
        }
        memory.setObject(blob as NSData, forKey: k as NSString, cost: blob.count)
        return (e.data, e.image)
    }

    func store(_ urlString: String, data: LinkPreviewData, image: Data?) {
        let k = urlString
        guard let blob = try? JSONEncoder().encode(Entry(data: data, image: image, storedAt: Date())) else { return }
        memory.setObject(blob as NSData, forKey: k as NSString, cost: blob.count)
        guard let name = fileName(urlString) else { return }
        // Off the caller's thread: this runs from a network completion handler.
        let url = dir.appendingPathComponent(name)
        DispatchQueue.global(qos: .utility).async {
            guard let sealed = try? AtRestCipher.shared.seal(blob) else { return }
            try? sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    /// __PLAINTEXT_LEFTOVERS_2026_09_24__ Session éphémère pour les aperçus: ni
    /// cache HTTP sur disque (la page et l'image d'un lien reçu), ni cookies
    /// déposés par des sites tiers dans le magasin partagé de l'app.
    static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config)
    }()
}

class LinkPreviewFetcher: ObservableObject {
    @Published var previewData: LinkPreviewData?
    @Published var isLoading = false
    @Published var previewImage: Data?

    private var currentTask: URLSessionDataTask?
    private var currentURLString: String?

    /// __CHAT_OPEN_PERF_2026_11_14__ The OG image, decoded. The bubbles used to
    /// write `UIImage(data: previewImage)` in their body: a fresh image on every
    /// pass, so the JPEG was inflated again at draw time, on main, on every
    /// re-render of every link bubble. Decoded once per URL (shared cache),
    /// downsampled to the bubble.
    @MainActor
    var previewUIImage: UIImage? {
        guard let data = previewImage else { return nil }
        return MediaAttachmentCache.shared.decodedImage(from: data,
                                                        id: "linkpreview|" + (currentURLString ?? ""),
                                                        maxDimension: 1200)
    }

    /// __LEAK_FIX_2026_09_25__ `allowNetwork` must be true ONLY for a link the user sent
    /// themselves. Fetching a RECEIVED link hands the sender the recipient's IP address
    /// and the moment the message was read (a tracking pixel by another name). Default
    /// false: an incoming link renders as plain text, or from the local cache if this
    /// device already fetched the same URL for an outgoing message.
    func fetchPreview(for urlString: String, allowNetwork: Bool = false) {
        OshiLog.messaging.info("🔍 LinkPreview: Starting fetch")

        // __STRICT_SECURITY_MODE_2026_11_22__ no preview at all — not even from
        // the cache — while strict mode is on.
        guard StrictSecurityMode.allowsLinkPreviews else {
            previewData = nil
            previewImage = nil
            isLoading = false
            return
        }

        guard let url = URL(string: urlString) else {
            OshiLog.messaging.info("❌ LinkPreview: Invalid URL - cannot create URL object")
            return
        }
        currentURLString = urlString

        // Cache hit: fill in and return without touching the network at all.
        if let (cached, image) = LinkPreviewCache.shared.lookup(urlString) {
            OshiLog.messaging.info("⚡️ LinkPreview: cache hit — no network")
            previewData = cached
            previewImage = image
            isLoading = false
            return
        }

        // __LEAK_FIX_2026_09_25__ never hit the network for a link we received.
        guard allowNetwork else {
            isLoading = false
            return
        }

        // Cancel any existing task
        currentTask?.cancel()
        
        isLoading = true
        OshiLog.messaging.info("⏳ LinkPreview: Loading preview... isLoading set to true")
        
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 15_0 like Mac OS X) AppleWebKit/605.1.15", forHTTPHeaderField: "User-Agent")
        
        OshiLog.messaging.info("📡 LinkPreview: Starting network request to \(url.absoluteString)")
        
        currentTask = LinkPreviewCache.session.dataTask(with: request) { [weak self] data, response, error in
            if let error = error {
                OshiLog.messaging.info("❌ LinkPreview: Network error - \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self?.isLoading = false
                    OshiLog.messaging.info("⏸ LinkPreview: isLoading set to false (error)")
                }
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                OshiLog.messaging.info("📡 LinkPreview: HTTP Status Code - \(httpResponse.statusCode)")
                if httpResponse.statusCode != 200 {
                    OshiLog.messaging.info("⚠️ LinkPreview: Non-200 status code received")
                }
            }
            
            guard let self = self,
                  let data = data,
                  let htmlString = String(data: data, encoding: .utf8) else {
                OshiLog.messaging.info("❌ LinkPreview: No data or invalid HTML encoding")
                DispatchQueue.main.async {
                    self?.isLoading = false
                    OshiLog.messaging.info("⏸ LinkPreview: isLoading set to false (no data)")
                }
                return
            }
            
            OshiLog.messaging.info("✅ LinkPreview: HTML fetched successfully, length: \(htmlString.count) characters")
            
            let preview = self.parseHTML(htmlString, url: urlString)
            OshiLog.messaging.info("✅ LinkPreview: Parsed metadata:")
            OshiLog.messaging.info("   - Title: \(preview.title ?? "none")")
            OshiLog.messaging.info("   - Description: \(preview.description?.prefix(50) ?? "none")...")
            OshiLog.messaging.info("   - Image URL: \(preview.imageURL ?? "none")")
            OshiLog.messaging.info("   - Site Name: \(preview.siteName ?? "none")")
            
            DispatchQueue.main.async {
                self.previewData = preview
                self.isLoading = false
                OshiLog.messaging.info("✅ LinkPreview: Preview data set, isLoading = false")

                // Cache the metadata now, before the image. If the image download
                // fails or the page has none, the expensive HTML fetch is still
                // saved and the next open is instant.
                LinkPreviewCache.shared.store(urlString, data: preview, image: nil)

                // Fetch image if available
                if let imageURLString = preview.imageURL,
                   let imageURL = URL(string: imageURLString) {
                    OshiLog.messaging.info("🖼 LinkPreview: Fetching image from: \(imageURL.absoluteString)")
                    self.fetchImage(from: imageURL)
                } else {
                    OshiLog.messaging.info("⚠️ LinkPreview: No image URL found in metadata")
                }
            }
        }
        
        currentTask?.resume()
        OshiLog.messaging.info("🚀 LinkPreview: Network request started")
    }
    
    private func fetchImage(from url: URL) {
        OshiLog.messaging.info("🖼 LinkPreview: Starting image download from: \(url.absoluteString)")
        
        LinkPreviewCache.session.dataTask(with: url) { [weak self] data, response, error in
            if let error = error {
                OshiLog.messaging.info("❌ LinkPreview: Image download error - \(error.localizedDescription)")
                return
            }
            
            guard let data = data else {
                OshiLog.messaging.info("❌ LinkPreview: No image data received")
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                OshiLog.messaging.info("📡 LinkPreview: Image HTTP Status - \(httpResponse.statusCode)")
            }
            
            OshiLog.messaging.info("✅ LinkPreview: Image downloaded successfully, size: \(data.count) bytes")
            
            DispatchQueue.main.async {
                self?.previewImage = data
                OshiLog.messaging.info("✅ LinkPreview: Preview image set in UI")
                // Re-store WITH the image, replacing the metadata-only entry
                // written a moment ago, so the next open needs no network at all.
                if let self, let key = self.currentURLString, let meta = self.previewData {
                    LinkPreviewCache.shared.store(key, data: meta, image: data)
                }
            }
        }.resume()
    }
    
    // MARK: - __OG_ATTRIBUTE_ORDER_2026_08_26__ Lire les metadonnees

    /// Les tags `<meta …>` / `<link …>` / `<title …>` de la page.
    ///
    /// 🔴 CE QUI NE MARCHAIT PAS. Les motifs precedents exigeaient un ORDRE
    /// d'attributs strict — `<meta\s+property="og:image"\s+content="…"` — donc
    /// `property` COLLE a `<meta` et `content` juste apres. C'est une forme
    /// parmi d'autres, pas la forme du HTML.
    ///
    /// Mesure sur `independent.co.uk` le 2026-08-26 : la page est rendue par
    /// react-helmet, qui pose `data-rh="true"` en PREMIER sur chaque tag du
    /// `<head>` :
    ///
    ///     <meta data-rh="true" property="og:image" content="https://…">
    ///     <title data-rh="true">…</title>
    ///     <link data-rh="true" rel="icon" type="image/png" href="…">
    ///
    /// Les SEPT motifs echouaient — pas seulement l'image : le titre, la
    /// description, le nom du site et la favicon aussi, y compris le repli
    /// `<title>` qui n'admettait aucun attribut. L'apercu etait donc VIDE, et
    /// vide de tout, pendant que Telegram affichait la carte complete.
    ///
    /// La lecon: un tag HTML est un SAC d'attributs, pas une chaine. On les
    /// extrait puis on interroge le sac. L'ordre, les attributs en trop, les
    /// guillemets simples et la casse cessent tous de compter.
    private static func attributes(of tag: String) -> [String: String] {
        var out: [String: String] = [:]
        let pattern = "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')"
        guard let re = try? NSRegularExpression(pattern: pattern) else { return out }
        let range = NSRange(tag.startIndex..., in: tag)
        for m in re.matches(in: tag, range: range) {
            guard let kr = Range(m.range(at: 1), in: tag) else { continue }
            let valueRange = m.range(at: 2).location != NSNotFound ? m.range(at: 2) : m.range(at: 3)
            guard let vr = Range(valueRange, in: tag) else { continue }
            let key = String(tag[kr]).lowercased()
            if out[key] == nil { out[key] = decodeEntities(String(tag[vr])) }
        }
        return out
    }

    /// Le strict minimum, et deliberement pas un decodeur HTML complet: ces
    /// cinq-la couvrent ce qu'on rencontre dans un `content=` d'Open Graph.
    /// `&amp;` compte le plus — une URL d'image porte presque toujours une
    /// chaine de requete.
    private static func decodeEntities(_ s: String) -> String {
        s.replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            // En DERNIER: sinon `&amp;lt;` se decoderait deux fois.
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    /// Toutes les paires `property|name` -> `content` du document.
    private static func metaTags(in html: String) -> [String: String] {
        var out: [String: String] = [:]
        guard let re = try? NSRegularExpression(pattern: "<meta\\b[^>]*>",
                                                options: [.caseInsensitive]) else { return out }
        let range = NSRange(html.startIndex..., in: html)
        for m in re.matches(in: html, range: range) {
            guard let tr = Range(m.range, in: html) else { continue }
            let attrs = attributes(of: String(html[tr]))
            guard let content = attrs["content"], !content.isEmpty else { continue }
            // `property` est la forme Open Graph, `name` celle de Twitter et des
            // meta classiques. Les deux existent en vrai, on accepte les deux.
            for key in [attrs["property"], attrs["name"]].compactMap({ $0?.lowercased() })
            where out[key] == nil {
                out[key] = content
            }
        }
        return out
    }

    /// Le `href` de la premiere `<link rel="…icon…">`.
    private static func iconHref(in html: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: "<link\\b[^>]*>",
                                                options: [.caseInsensitive]) else { return nil }
        let range = NSRange(html.startIndex..., in: html)
        for m in re.matches(in: html, range: range) {
            guard let tr = Range(m.range, in: html) else { continue }
            let attrs = attributes(of: String(html[tr]))
            // `rel` vaut souvent plusieurs mots ("shortcut icon", "apple-touch-icon").
            guard let rel = attrs["rel"]?.lowercased(), rel.contains("icon"),
                  let href = attrs["href"], !href.isEmpty else { continue }
            return href
        }
        return nil
    }

    /// Le `<title>`, avec ou sans attributs.
    private static func titleTag(in html: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: "<title\\b[^>]*>([^<]*)</title>",
                                                options: [.caseInsensitive]) else { return nil }
        let range = NSRange(html.startIndex..., in: html)
        guard let m = re.firstMatch(in: html, range: range),
              let r = Range(m.range(at: 1), in: html) else { return nil }
        let t = decodeEntities(String(html[r])).trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    func parseHTML(_ html: String, url: String) -> LinkPreviewData {
        let meta = Self.metaTags(in: html)

        // L'ordre EST la regle de repli, du plus intentionnel au plus generique.
        // `og:image:secure_url` et les variantes Twitter sont la parce que
        // certains sites ne posent que celles-la.
        let title = meta["og:title"] ?? meta["twitter:title"] ?? Self.titleTag(in: html)
        let description = meta["og:description"]
            ?? meta["twitter:description"]
            ?? meta["description"]
        let image = meta["og:image"]
            ?? meta["og:image:secure_url"]
            ?? meta["og:image:url"]
            ?? meta["twitter:image"]
            ?? meta["twitter:image:src"]

        return LinkPreviewData(
            url: url,
            title: title,
            description: description,
            imageURL: image.map { makeAbsoluteURL($0, baseURL: url) },
            siteName: meta["og:site_name"] ?? extractDomain(from: url),
            favicon: Self.iconHref(in: html).map { makeAbsoluteURL($0, baseURL: url) }
        )
    }
    
    private func makeAbsoluteURL(_ urlString: String, baseURL: String) -> String {
        if urlString.starts(with: "http") {
            return urlString
        }
        
        guard let base = URL(string: baseURL) else { return urlString }
        
        if urlString.starts(with: "//") {
            return (base.scheme ?? "https") + ":" + urlString
        }
        
        if urlString.starts(with: "/") {
            return (base.scheme ?? "https") + "://" + (base.host ?? "") + urlString
        }
        
        return urlString
    }
    
    /// Les suffixes publics en DEUX etiquettes qu'on rencontre reellement.
    ///
    /// Liste volontairement courte: elle ne pretend pas remplacer la Public
    /// Suffix List, elle empeche le cas majoritaire de se tromper. Un suffixe
    /// absent d'ici retombe sur l'ancien comportement, qui est correct pour
    /// tous les domaines a une seule etiquette de TLD.
    private static let twoLabelSuffixes: Set<String> = [
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp",
        "co.nz", "co.za", "co.in", "co.kr", "co.il", "co.id", "co.th",
        "com.br", "com.mx", "com.ar", "com.tr", "com.cn", "com.hk",
        "com.sg", "com.tw", "com.pl", "com.es", "com.pt", "com.ua"
    ]

    /// Le nom « joli » du site, quand la page n'a pas dit le sien.
    ///
    /// 🔴 __TWO_LABEL_TLD_2026_08_26__ L'ancienne version prenait
    /// `components[count - 2]` sans condition. Pour `www.independent.co.uk`
    /// cela designe **`co`**, et le lien s'annoncait comme venant de « Co » —
    /// vrai pour tout `.co.uk`, `.com.au`, `.co.jp`… c'est-a-dire pour une
    /// bonne part de la presse anglophone.
    ///
    /// Il faut sauter le suffixe AVANT de compter: `independent.co.uk` porte
    /// trois etiquettes de suffixe apparent, pas deux.
    private func extractDomain(from urlString: String) -> String? {
        guard let url = URL(string: urlString),
              let host = url.host else { return nil }

        var parts = host.lowercased().components(separatedBy: ".")
        if parts.first == "www" { parts.removeFirst() }
        guard parts.count >= 2 else { return host.capitalized }

        let lastTwo = parts.suffix(2).joined(separator: ".")
        // `independent.co.uk` -> on retire `co.uk`, il reste `independent`.
        let dropped = Self.twoLabelSuffixes.contains(lastTwo) ? 2 : 1
        guard parts.count > dropped else { return parts.joined(separator: ".") }
        return parts[parts.count - dropped - 1].capitalized
    }
    
    func cancel() {
        currentTask?.cancel()
        isLoading = false
    }
}

// MARK: - Link Preview View (WhatsApp Style)

struct LinkPreviewView: View {
    let previewData: LinkPreviewData
    let previewImage: Data?
    let onTap: () -> Void
    
    @Environment(\.colorScheme) var colorScheme
    
    private var backgroundColor: Color {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)
    }
    
    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 0) {
                // Image (if available)
                if let imageData = previewImage,
                   let uiImage = UIImage(data: imageData) {
                    GeometryReader { geo in
                        Image(uiImage: uiImage)
                            .resizable()
                            .aspectRatio(contentMode: .fill)
                            .frame(width: geo.size.width, height: 180)
                            .clipped()
                    }
                    .frame(height: 180)
                    .frame(maxWidth: .infinity)
                    .accessibilityHidden(true)
                }

                // Content - Fixed layout to prevent text cutoff
                VStack(alignment: .leading, spacing: 6) {
                    // Site name or domain
                    if let siteName = previewData.siteName {
                        Text(siteName.uppercased())
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    // Title
                    Text(previewData.displayTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.primary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)

                    // Description - Fixed: proper padding and no horizontal cutoff
                    if let description = previewData.displayDescription {
                        Text(description)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(3)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    // URL
                    Text(previewData.url)
                        .font(.caption2)
                        .foregroundColor(.blue)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: 320, alignment: .leading)
            .background(backgroundColor)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(Color.gray.opacity(0.2), lineWidth: 1)
            )
        }
        .buttonStyle(PlainButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityText))
        .accessibilityHint(Text("link_preview.open_hint".localized))
        .accessibilityAddTraits(.isLink)
    }

    private var accessibilityText: String {
        var parts: [String] = []
        if let siteName = previewData.siteName { parts.append(siteName) }
        parts.append(previewData.displayTitle)
        if let description = previewData.displayDescription { parts.append(description) }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Link Detection Extension

extension String {
    /// __GROUP_OPEN_HITCH_2026_11_22__ Same answer as before, now pre-checked,
    /// cached per text and served by a reused detector — see LinkDetection.
    /// It used to allocate a fresh `NSDataDetector` for every bubble's
    /// `onAppear` on the main thread (~27 ms of a group open on device).
    func detectFirstURL() -> String? {
        LinkDetection.firstURL(in: self)
    }
}

// MARK: - Preview Loading View

struct LinkPreviewLoadingView: View {
    @Environment(\.colorScheme) var colorScheme
    
    private var backgroundColor: Color {
        colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray5)
    }
    
    var body: some View {
        HStack(spacing: 12) {
            ProgressView()
                .scaleEffect(0.8)
                .accessibilityHidden(true)

            Text("link_preview_loading".localized)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(12)
        .frame(maxWidth: 320)
        .background(backgroundColor)
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text("link_preview_loading".localized))
    }
}
