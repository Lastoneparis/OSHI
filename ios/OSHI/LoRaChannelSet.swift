import Foundation

/// `https://meshtastic.org/e/#…` — the link every public Meshtastic channel is
/// shared in, parsed and produced.
///
/// # Why this exists
///
/// ``LoRaChannel`` already let a user type a channel name and key, which is
/// enough to join a mesh whose credentials you already have. But nobody shares a
/// public channel that way: every community mesh, every regional channel, every
/// "join us" post is a LINK and a QR code of that link. Without the format,
/// joining the public mesh for your city meant reading 32 characters of base64
/// off someone's screenshot by hand, or installing the Meshtastic app to import
/// it and coming back. "See and join public channels" is this format.
///
/// It is equally how a mesh is HANDED OVER, which matters just as much: someone
/// who has set a mesh up for a protest or a hike needs to give it to the people
/// with them, and they will not type a PSK.
///
/// # What is on the wire
///
/// The fragment after `/#` is a base64url `ChannelSet` protobuf:
///
///     ChannelSet       { settings = 1 (repeated), lora_config = 2 }
///     ChannelSettings  { psk = 2, name = 3 }
///     LoRaConfig       { use_preset = 1, modem_preset = 2, region = 7, hop_limit = 8 }
///
/// The FIRST entry of `settings` is the primary channel — "It includes only one
/// PRIMARY channel (which must be first) and any SECONDARY channels", from
/// `apponly.proto` itself. Order is meaning, not presentation, and this preserves
/// it.
///
/// ⚠️ A channel link is NOT a way to share a secret. The PSK travels in the link
/// in plain base64, so anyone who sees the link is on the channel. That is
/// inherent to the format, and the UI says so rather than implying a link is safe
/// to post in public.
///
/// # Kept in step with Android
///
/// `OSHI-Android/.../service/lora/LoRaChannelSet.kt`, field for field. The Kotlin
/// carries the unit tests that pin the byte layout against values derived from the
/// published protos; this is its mirror, and the two must stay interchangeable or
/// a link shared from one phone will not open on the other.
public enum LoRaChannelSet {

    /// What we PRODUCE. Parsing is deliberately more tolerant — see ``parse(_:)``.
    public static let urlPrefix = "https://meshtastic.org/e/#"

    /// `Channel.Role`. A link never carries DISABLED entries.
    public static let rolePrimary: UInt64 = 1
    public static let roleSecondary: UInt64 = 2

    /// The firmware holds eight slots, 0-7, and slot 0 is the primary. A link
    /// with more is malformed rather than interesting.
    public static let maxChannels = 8

    public struct Channel: Equatable {
        /// Blank is legitimate: it is the stock LongFast channel.
        public let name: String
        public let psk: Data

        public init(name: String, psk: Data) {
            self.name = name
            self.psk = psk
        }

        /// For display. Blank means the default, which users know as LongFast.
        public var displayName: String { LoRaChannel.displayName(name) }

        /// The public default key — one byte, value 1 (`AQ==`), shared by every
        /// stock Meshtastic install. Worth saying out loud: it means the channel
        /// is readable by anyone, which is what makes it "public" and is also
        /// exactly what a user at a protest must not mistake for privacy.
        public var isDefaultPublicKey: Bool { psk.count == 1 && psk.first == 1 }

        /// Empty, or a single zero byte: the radio transmits in the CLEAR.
        public var isUnencrypted: Bool { psk.isEmpty || (psk.count == 1 && psk.first == 0) }
    }

    /// The radio half of a link.
    ///
    /// Every field is optional because a link may carry no `lora_config` at all,
    /// and because applying a PARTIAL radio config is what `adminSetLoRaConfig`
    /// warns about: the whole LoRaConfig is replaced, so a field left out arrives
    /// as zero and produces a node that is on the air and unable to talk to
    /// anyone. A caller must have all three or write none, and nil is how it finds
    /// out.
    public struct Radio: Equatable {
        public let regionRaw: UInt64?
        public let presetRaw: UInt64?
        public let hopLimit: UInt64?

        public init(regionRaw: UInt64?, presetRaw: UInt64?, hopLimit: UInt64?) {
            self.regionRaw = regionRaw
            self.presetRaw = presetRaw
            self.hopLimit = hopLimit
        }

        public var isComplete: Bool {
            regionRaw != nil && presetRaw != nil && hopLimit != nil
        }
    }

    public struct Parsed: Equatable {
        /// Primary first, then secondaries, in the link's own order.
        public let channels: [Channel]
        public let radio: Radio?

        public var primary: Channel? { channels.first }
        public var secondaries: [Channel] { Array(channels.dropFirst()) }
    }

    // MARK: - Parse

    /// The ``Parsed`` form of `url`, or nil when it is not a channel link.
    ///
    /// Returns nil rather than throwing: this is fed by a paste field and a QR
    /// scan, so "not a channel link" is an ordinary thing for a user to do and the
    /// caller has to say so in a sentence, not catch an error.
    public static func parse(_ url: String) -> Parsed? {
        guard let payload = fragment(of: url),
              let bytes = decodeBase64URL(payload),
              !bytes.isEmpty else { return nil }
        return parseChannelSet(bytes)
    }

    /// The base64 payload of `url`.
    ///
    /// Splits on `/#` exactly as the reference `setURL` does, which is what makes
    /// `/e/#`, `/d/#` and the 2021 `/c/#` all work, and takes the LAST segment so
    /// a link wrapped in redirect junk still resolves. A bare fragment with no
    /// prefix is also accepted — people paste the half they can see, especially
    /// off a QR reader that shows only the fragment.
    private static func fragment(of url: String) -> String? {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return nil }

        let addOnly = "/?add=true#"
        let marker = trimmed.contains(addOnly) ? addOnly : "/#"
        if let range = trimmed.range(of: marker, options: .backwards) {
            let payload = String(trimmed[range.upperBound...])
            return payload.isEmpty ? nil : payload
        }
        // No delimiter at all. Accept a bare payload, but only when it LOOKS like
        // base64 — otherwise every stray paste is reported as a BAD link rather
        // than as not a link at all.
        if trimmed.contains(":") || trimmed.contains("/") || trimmed.contains(" ") { return nil }
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/-_=")
        return trimmed.allSatisfy { allowed.contains($0) } ? trimmed : nil
    }

    /// base64url (and plain base64) to bytes, padding restored.
    ///
    /// Both alphabets are accepted for the same reason ``LoRaChannel/parsePSK(_:)``
    /// accepts both: a link copied through a chat app, a QR reader or a forum post
    /// arrives in whichever one that tool used, and the user cannot tell by
    /// looking.
    ///
    /// A length of `n % 4 == 1` is not short padding, it is impossible base64, and
    /// padding it to four would decode to silent nonsense. The reference appends
    /// `"=" * (4 - len % 4)` unconditionally, which also appends four `=` when the
    /// length is already a multiple of four; we do neither.
    private static func decodeBase64URL(_ raw: String) -> Data? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.hasSuffix("=") { s.removeLast() }
        if s.isEmpty { return nil }
        if s.count % 4 == 1 { return nil }
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        return Data(base64Encoded: s)
    }

    /// `ChannelSet { settings = 1 (repeated), lora_config = 2 }`.
    private static func parseChannelSet(_ d: Data) -> Parsed? {
        var channels: [Channel] = []
        var radio: Radio?
        for f in parseProtoFields(d) {
            if f.number == 1, f.wire == 2, let c = parseChannelSettings(f.payload) {
                channels.append(c)
            } else if f.number == 2, f.wire == 2 {
                radio = parseLoRaConfig(f.payload)
            }
        }
        // No channels at all is not a channel link, whatever else it decoded to.
        // Random bytes parse as protobuf far more often than feels right, so this
        // is the check that stops a stray paste being offered to the user as a
        // mesh they can join.
        if channels.isEmpty { return nil }
        return Parsed(channels: Array(channels.prefix(maxChannels)), radio: radio)
    }

    /// `ChannelSettings { psk = 2, name = 3 }`.
    private static func parseChannelSettings(_ d: Data) -> Channel? {
        var psk = Data()
        var name = ""
        var sawAnything = false
        for f in parseProtoFields(d) {
            if f.number == 2, f.wire == 2 {
                psk = f.payload
                sawAnything = true
            } else if f.number == 3, f.wire == 2 {
                name = String(data: f.payload, encoding: .utf8) ?? ""
                sawAnything = true
            }
        }
        // An entry with NEITHER a name nor a key is a disabled slot some encoder
        // wrote out anyway; it is not a channel anyone can join.
        guard sawAnything else { return nil }
        return Channel(name: name, psk: psk)
    }

    /// `LoRaConfig { modem_preset = 2, region = 7, hop_limit = 8 }`.
    private static func parseLoRaConfig(_ d: Data) -> Radio {
        var region: UInt64?
        var preset: UInt64?
        var hop: UInt64?
        for f in parseProtoFields(d) where f.wire == 0 {
            switch f.number {
            case 2: preset = f.varintValue
            case 7: region = f.varintValue
            case 8: hop = f.varintValue
            default: break
            }
        }
        return Radio(regionRaw: region, presetRaw: preset, hopLimit: hop)
    }

    // MARK: - Build

    /// A shareable link for `channels`, primary first.
    ///
    /// `radio` is omitted unless ``Radio/isComplete``: a link carrying a partial
    /// LoRaConfig would reconfigure the recipient's radio to zeros on import, for
    /// the reason `adminSetLoRaConfig` documents. Better to share a channel alone
    /// than to break the radio of everyone who scans it.
    ///
    /// Returns nil for an empty channel list, since a link to no mesh is not a
    /// link.
    public static func buildURL(channels: [Channel], radio: Radio? = nil) -> String? {
        guard !channels.isEmpty else { return nil }
        var set = ProtoWriter()
        for c in channels.prefix(maxChannels) {
            var settings = ProtoWriter()
            settings.bytes(2, c.psk)
            settings.bytes(3, Data(LoRaChannel.clampName(c.name).utf8))
            set.bytes(1, settings.data)
        }
        if let r = radio, r.isComplete {
            var lora = ProtoWriter()
            lora.varint(1, 1)              // use_preset = true
            lora.varint(2, r.presetRaw!)   // modem_preset
            lora.varint(7, r.regionRaw!)   // region
            lora.varint(8, r.hopLimit!)    // hop_limit
            set.bytes(2, lora.data)
        }
        return urlPrefix + encodeBase64URL(set.data)
    }

    /// base64url, padding stripped — byte for byte what the reference `getURL`
    /// produces, and what the Kotlin produces.
    private static func encodeBase64URL(_ d: Data) -> String {
        d.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
