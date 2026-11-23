//
//  LoRaRegion.swift
//  OSHI — the regulatory region a LoRa node transmits in.
//
//  WHY THIS IS NOT OPTIONAL
//  ------------------------
//  A freshly flashed node boots with no region and REFUSES TO TRANSMIT. Measured
//  on a real Heltec V3 on 2026-08-03, straight after flashing:
//
//      INFO | Wanted region 0, using UNSET
//      INFO | Set radio: region=UNSET, name=LongFast, config=0, ch=19, power=30
//
//  So "flash it, pair it, start messaging" is missing a step: without a region
//  the node pairs happily over Bluetooth, shows as connected, and silently sends
//  nothing. This is the single most important thing OSHI must handle for someone
//  with a node in their pocket.
//
//  It is a legal constraint, not a preference. Which frequencies and power a
//  radio may use is set by the country's spectrum regulator, so OSHI proposes a
//  region from the device's own locale and asks the user to confirm — it must
//  never be silently guessed, and it must never be left unset.
//

import Foundation

/// Meshtastic's `Config.LoRaConfig.RegionCode`.
///
/// The raw values are the wire enum and MUST match the firmware; they are not
/// ours to renumber. Ordering here is by how commonly it will be picked, which
/// is only a UI concern.
public enum LoRaRegion: UInt64, CaseIterable, Identifiable, Sendable {
    case unset   = 0
    case us      = 1
    case eu433   = 2
    case eu868   = 3
    case cn      = 4
    case jp      = 5
    case anz     = 6
    case kr      = 7
    case tw      = 8
    case ru      = 9
    case ind     = 10
    case nz865   = 11
    case th      = 12
    case lora24  = 13
    case ua433   = 14
    case ua868   = 15
    case my433   = 16
    case my919   = 17
    case sg923   = 18
    case ph433   = 19
    case ph868   = 20
    case ph915   = 21
    case anz433  = 22
    case kz433   = 23
    case kz863   = 24
    case np865   = 25
    case br902   = 26

    public var id: UInt64 { rawValue }

    /// What the user sees. Region first, band second — someone in France looks
    /// for "Europe", not for "868 MHz".
    public var displayName: String {
        switch self {
        case .unset:  return "lora.region.unset".localized
        case .us:     return "United States · 915 MHz"
        case .eu433:  return "Europe · 433 MHz"
        case .eu868:  return "Europe · 868 MHz"
        case .cn:     return "China · 470 MHz"
        case .jp:     return "Japan · 920 MHz"
        // Chile's community mesh uses the same 915–928 MHz plan. Meshtastic
        // exposes that plan as ANZ rather than a Chile-specific enum value.
        case .anz:    return "Australia / New Zealand · 915 MHz"
        case .kr:     return "Korea · 920 MHz"
        case .tw:     return "Taiwan · 920 MHz"
        case .ru:     return "Russia · 868 MHz"
        case .ind:    return "India · 865 MHz"
        case .nz865:  return "New Zealand · 865 MHz"
        case .th:     return "Thailand · 920 MHz"
        case .lora24: return "2.4 GHz (worldwide)"
        case .ua433:  return "Ukraine · 433 MHz"
        case .ua868:  return "Ukraine · 868 MHz"
        case .my433:  return "Malaysia · 433 MHz"
        case .my919:  return "Malaysia · 919 MHz"
        case .sg923:  return "Singapore · 923 MHz"
        case .ph433:  return "Philippines · 433 MHz"
        case .ph868:  return "Philippines · 868 MHz"
        case .ph915:  return "Philippines · 915 MHz"
        case .anz433: return "Australia / New Zealand · 433 MHz"
        case .kz433:  return "Kazakhstan · 433 MHz"
        case .kz863:  return "Kazakhstan · 863 MHz"
        case .np865:  return "Nepal · 865 MHz"
        case .br902:  return "Brazil · 902 MHz"
        }
    }

    /// Everything except the placeholder, for a picker.
    public static var selectable: [LoRaRegion] {
        allCases.filter { $0 != .unset }
    }

    /// The region we PROPOSE for an ISO country code.
    ///
    /// A suggestion the user confirms, never an automatic write: getting this
    /// wrong means transmitting on a band the user is not allowed to use, and
    /// the phone's locale is not proof of where the node will actually live.
    /// An unmapped country returns nil so the UI asks rather than inventing.
    public static func suggested(forRegionCode code: String) -> LoRaRegion? {
        switch code.uppercased() {
        // EU 868 — the ETSI band, by far the most common in Europe.
        case "FR", "DE", "ES", "IT", "PT", "NL", "BE", "LU", "AT", "CH", "IE",
             "DK", "SE", "NO", "FI", "IS", "PL", "CZ", "SK", "HU", "RO", "BG",
             "GR", "HR", "SI", "EE", "LV", "LT", "GB", "MT", "CY":
            return .eu868
        case "US", "CA", "MX", "PR":              return .us
        // 902-928 MHz, the same band the US code covers. Meshtastic has no
        // region code of its own for these countries, and a node bought in
        // Santiago or Lima IS a 915 MHz node — so answering nil left a Chilean
        // user (Play review, 2026-10-25) with a picker that opened on nothing
        // and a node that pairs and never transmits.
        //
        // Brazil is deliberately NOT here: it has its own code (.br902) with
        // its own rules, and it is already mapped below.
        case "CL", "AR", "PE", "CO", "EC", "BO", "PY", "UY", "VE",
             "CR", "PA", "GT", "SV", "HN", "NI", "DO":
            return .us
        case "AU":                                return .anz
        case "NZ":                                return .nz865
        case "JP":                                return .jp
        case "KR":                                return .kr
        case "TW":                                return .tw
        case "CN":                                return .cn
        case "RU":                                return .ru
        case "UA":                                return .ua868
        case "IN":                                return .ind
        case "TH":                                return .th
        case "MY":                                return .my919
        case "SG":                                return .sg923
        case "PH":                                return .ph915
        case "KZ":                                return .kz863
        case "NP":                                return .np865
        case "BR":                                return .br902
        default:                                  return nil
        }
    }

    /// The device's best guess from its own locale, or nil when unmapped.
    public static var suggestedFromDevice: LoRaRegion? {
        let code = Locale.current.region?.identifier
            ?? Locale.current.identifier.split(separator: "_").last.map(String.init)
            ?? ""
        return suggested(forRegionCode: code)
    }
}

/// The radio preset. Only the two that matter to a user are offered: the default
/// everyone else is on, and the slow one that reaches further.
///
/// Raw values are Meshtastic's `Config.LoRaConfig.ModemPreset`.
public enum LoRaModemPreset: UInt64, CaseIterable, Identifiable, Sendable {
    case longFast = 0
    case longSlow = 1

    public var id: UInt64 { rawValue }

    public var displayName: String {
        switch self {
        case .longFast: return "lora.preset.longfast".localized
        case .longSlow: return "lora.preset.longslow".localized
        }
    }

    public var detail: String {
        switch self {
        case .longFast: return "lora.preset.longfast.detail".localized
        case .longSlow: return "lora.preset.longslow.detail".localized
        }
    }
}

// MARK: - Channel

/// The Meshtastic PRIMARY channel: what a user means by "the network".
///
/// Port of Android `LoRaChannel.kt`, same values, same refusals. Asked for by a
/// user in Chile (Play review, 2026-10-25): OSHI could set a node's region but
/// had nowhere to say WHICH mesh it joins, so being on a country's community
/// channel meant installing a second Meshtastic client, setting it there, and
/// coming back.
///
/// Two fields decide whether two radios hear each other at all:
///
/// - `name` — up to 11 bytes. The firmware stores it in a `char[12]`, so a 12th
///   byte is not "mostly fine": the node truncates it and the two ends then
///   disagree about the channel hash. Clamped by BYTES, like `clampShortName`.
/// - `psk` — a SINGLE byte is not a key, it is a key INDEX: 0 means no
///   encryption at all and 1 means "the default key", the public one every
///   stock Meshtastic install shares (the `AQ==` in channel URLs). 16 or 32
///   bytes is a real AES key.
///
/// An empty name with the default key is exactly what the Meshtastic app ships
/// — the channel it calls LongFast — which is why blank is not an error here.
public enum LoRaChannel {

    /// The firmware's `ChannelSettings.name` is a `char[12]`: 11 bytes + NUL.
    public static let nameMaxBytes = 11

    /// What a stock node calls its unnamed primary channel. DISPLAY ONLY —
    /// writing this as the name would produce a different channel hash from
    /// every default node out there, the opposite of what "LongFast by default"
    /// asks for.
    public static let defaultName = "LongFast"

    /// One byte, value 1: the key index meaning "the public default key".
    public static var defaultPSK: Data { Data([1]) }

    /// Key sizes the firmware accepts: an index byte, AES-128, or AES-256.
    private static let pskSizes: Set<Int> = [1, 16, 32]

    /// `raw` clamped to ``nameMaxBytes`` UTF-8 bytes, never cutting a code point
    /// in half. Blank stays blank — that is the LongFast default, not an error.
    public static func clampName(_ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.utf8.count > nameMaxBytes else { return trimmed }
        var out = ""
        var used = 0
        for ch in trimmed {
            let n = String(ch).utf8.count
            if used + n > nameMaxBytes { break }
            out.append(ch)
            used += n
        }
        return out
    }

    /// The key to write, or nil when `raw` is not one.
    ///
    /// Blank gives ``defaultPSK``. Both base64 alphabets are accepted and
    /// missing padding is tolerated, because a Meshtastic channel URL carries
    /// the key base64URL-encoded and unpadded and that URL is what a user
    /// pastes.
    ///
    /// Returns nil rather than falling back to the default on purpose: a
    /// mistyped key that silently became the public key would put a message the
    /// user believed was privately keyed onto the open channel.
    public static func parsePSK(_ raw: String) -> Data? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return defaultPSK }
        var normalised = t.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            .replacingOccurrences(of: "=", with: "")
        if normalised.isEmpty { return nil }
        normalised += String(repeating: "=", count: (4 - normalised.count % 4) % 4)
        guard let bytes = Data(base64Encoded: normalised),
              pskSizes.contains(bytes.count) else { return nil }
        return bytes
    }

    /// What to show for a channel name that may be blank.
    public static func displayName(_ name: String) -> String {
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? defaultName : t
    }
}
