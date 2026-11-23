//
//  GroupMentions.swift
//  OSHI — `@name` in a group message.
//
//  SHARES THE `@` SIGIL WITH TICKERS, so resolution order is the whole design:
//  a token that matches a group member is a MENTION, and only what is left over
//  is offered to the ticker parser. A person always wins over a stock symbol —
//  someone called "Max" being turned into a link to a chart is a much worse
//  failure than a ticker rendering as plain text.
//
//  Pure text → ranges. No network, no state, so it is safe to run on every
//  message as it renders.
//

import Foundation
import SwiftUI

enum GroupMentions {

    struct Match: Equatable {
        /// The member's public key — what the mention actually refers to.
        let publicKey: String
        /// The name as it will be shown, without the sigil.
        let name: String
        /// Range of the whole token INCLUDING the `@`, in the original string.
        let range: Range<String.Index>
    }

    /// A candidate token: `@` followed by name characters.
    ///
    /// Allows letters, digits, `_`, `.` and `-` because aliases are free text —
    /// "jean-luc", "max_99" and "a.rossi" are all names people actually use. The
    /// look-behind keeps it out of email addresses and URL paths, exactly as for
    /// tickers.
    private static let candidate = try! NSRegularExpression(
        pattern: "(?<![A-Za-z0-9_/.@&])@([\\p{L}\\p{N}_.\\-]{1,32})"
    )

    /// Mentions in `text`, resolved against `members`.
    ///
    /// `members` maps a display name to a public key. Matching is
    /// case-insensitive and diacritic-insensitive so "@Jean" finds "Jeán" — a
    /// mention that fails because of an accent is a mention that silently does
    /// not notify anyone.
    ///
    /// Longest name first, so "@jean-luc" is not consumed by a member called
    /// "jean".
    static func matches(in text: String, members: [String: String]) -> [Match] {
        guard text.contains("@"), !members.isEmpty else { return [] }

        // Normalised lookup, built once per call rather than per candidate.
        //
        // AMBIGUITY IS DROPPED, NOT RESOLVED. Folding case and accents means two
        // members called "Jean" and "Jeán" collapse to the same key, and a
        // dictionary would silently keep whichever was written last — so `@jean`
        // would notify one of them at random. Notifying the wrong person in a
        // group is a real harm and an invisible one, so a colliding name
        // resolves to NOBODY and renders as plain text. The author sees their
        // mention did not take and can disambiguate; nobody is quietly pinged
        // in someone else's place.
        var lookup: [String: (name: String, key: String)] = [:]
        var ambiguous: Set<String> = []
        for (name, key) in members {
            let folded = fold(name)
            if let existing = lookup[folded], existing.key != key {
                ambiguous.insert(folded)
            } else {
                lookup[folded] = (name, key)
            }
        }
        for f in ambiguous { lookup.removeValue(forKey: f) }
        let names = lookup.keys.sorted { $0.count > $1.count }

        var out: [Match] = []
        let ns = NSRange(text.startIndex..., in: text)
        for m in candidate.matches(in: text, range: ns) {
            guard let whole = Range(m.range, in: text),
                  let tokRange = Range(m.range(at: 1), in: text) else { continue }
            let token = fold(String(text[tokRange]))
            // Prefix match on the LONGEST member name the token starts with, so
            // "@jean-luc, are you there" resolves "jean-luc" and stops.
            guard let hit = names.first(where: { token.hasPrefix($0) }),
                  let member = lookup[hit] else { continue }
            // Trim the token back to just the matched name: the rest is prose.
            let nameLen = hit.count
            let tokenChars = text[tokRange]
            let end = tokenChars.index(tokenChars.startIndex, offsetBy: nameLen,
                                       limitedBy: tokenChars.endIndex) ?? tokenChars.endIndex
            let tokenEnd = end
            out.append(Match(publicKey: member.key,
                             name: member.name,
                             range: whole.lowerBound..<tokenEnd))
        }
        return out
    }

    /// True when `text` mentions `publicKey` — used to decide whether to notify
    /// someone about a group message they would otherwise have muted.
    static func mentions(_ publicKey: String, in text: String, members: [String: String]) -> Bool {
        matches(in: text, members: members).contains { $0.publicKey == publicKey }
    }

    /// Case- and accent-insensitive form, so "@Jeán" and "@jean" are the same
    /// person.
    private static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    // MARK: - Rendering on the RECEIVING device

    /// What a mention of `publicKey` should read as, on THIS device.
    ///
    /// A mention travels as a public key, not as text — so the sender's name for
    /// someone is not imposed on everyone else. Each device renders the name IT
    /// knows:
    ///
    ///   * the group's own member alias, if that member set one;
    ///   * otherwise this device's private contact alias;
    ///   * otherwise the first 5 characters of the key.
    ///
    /// The 5-character fallback matters more than it looks. Without it a reader
    /// who has not named that person sees a raw base64 key mid-sentence, which
    /// is unreadable and pushes the rest of the line off screen. Five characters
    /// is short enough to read as a label and long enough to tell two members
    /// apart in a group of any plausible size.
    static func displayName(for publicKey: String,
                            groupAlias: String? = nil) -> String {
        if let groupAlias, !groupAlias.isEmpty { return groupAlias }

        if let data = UserDefaults.standard.data(forKey: "contactAliases"),
           let aliases = try? JSONDecoder().decode([String: String].self, from: data),
           let local = aliases[publicKey], !local.isEmpty {
            return local
        }
        // __SHARED_NICKNAME_2026_09_22__ pas d'alias local: le pseudo partage.
        // (Rendu seulement — le fil porte toujours la CLE, voir `wireToken`.)
        if let nick = SharedNickname.nickname(for: publicKey) {
            return nick
        }

        return String(publicKey.prefix(5))
    }

    /// The wire form of a mention: the KEY, never the name.
    ///
    /// Sending the rendered name would freeze the sender's private nickname for
    /// someone into everyone else's copy of the thread — and would make the
    /// mention unmatchable on a device that knows that person by a different
    /// name.
    static func wireToken(for publicKey: String) -> String { "@[\(publicKey)]" }

    /// Every key mentioned in a message that has already been put on the wire.
    static func wireMentions(in text: String) -> [String] {
        guard text.contains("@[") else { return [] }
        let re = try! NSRegularExpression(pattern: "@\\[([A-Za-z0-9+/=_-]{20,})\\]")
        let ns = NSRange(text.startIndex..., in: text)
        return re.matches(in: text, range: ns).compactMap {
            Range($0.range(at: 1), in: text).map { r in String(text[r]) }
        }
    }

    /// Replace wire tokens with the name THIS device knows, for display.
    static func render(_ text: String, groupAliases: [String: String] = [:]) -> String {
        guard text.contains("@[") else { return text }
        var out = text
        for key in wireMentions(in: text) {
            let name = displayName(for: key, groupAlias: groupAliases[key])
            out = out.replacingOccurrences(of: wireToken(for: key), with: "@" + name)
        }
        return out
    }
}
