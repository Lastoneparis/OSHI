//
//  CashuPayCommand.swift
//  OSHI
//
//  🪙 `/pay <token> [public]` — put a Cashu token in this conversation.
//
//  Swift half of `OSHI-Android/.../util/CashuPayCommand.kt`. Pure text →
//  decision: no SwiftUI, no managers, no I/O, so the rules are the same on
//  both platforms and both can be read side by side.
//
//  Two refusals carry the whole command.
//
//  1. NOTHING THAT DOES NOT DECODE GOES ON THE WIRE. A typo in a pasted token
//     is money thrown away — the sender believes they paid, the recipient
//     holds a string no wallet will take, and neither of them ever learns why.
//
//  2. A BEARER TOKEN INTO A GROUP NEEDS THE WORD `public`. Everyone who can
//     read the thread can spend it and the first to redeem wins, so the plain
//     form does not send: it names the amount that was about to be exposed and
//     asks again. One extra word against giving a stranger money by reflex.
//

import Foundation

enum CashuPayCommand {

    enum Outcome: Equatable {
        /// `/pay` alone — show how to use it.
        case usage
        /// The argument is not a Cashu token at all.
        case notAToken
        /// It looks like one but does not decode, or has no amount.
        case invalid
        /// Send it. `summary` is "10 sat", for the confirmation line.
        case send(token: CashuTokens.Token, summary: String)
        /// A group, and `public` was not written. Nothing is sent.
        case needsPublicConfirm(summary: String)
    }

    static let prefix = "/pay"

    /// True when `text` is this command, so the composer can route it.
    static func handles(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t == prefix || t.hasPrefix(prefix + " ")
    }

    /// - Parameter isGroup: a thread more than one other person can read.
    ///   OSHI's groups are the equivalent of the public channel this command
    ///   guards against elsewhere.
    static func parse(_ text: String, isGroup: Bool) -> Outcome {
        var args = text.trimmingCharacters(in: .whitespacesAndNewlines)
        args = String(args.dropFirst(prefix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !args.isEmpty else { return .usage }

        var parts = args.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        // The confirmation word is a TRAILING argument, so a token is never
        // mistaken for it and `public` is never mistaken for a token.
        let confirmedPublic = parts.count > 1 && parts.last?.lowercased() == "public"
        if confirmedPublic { parts.removeLast() }

        guard parts.count == 1, CashuTokens.bareToken(from: parts[0]) != nil else { return .notAToken }
        guard let token = CashuTokens.decode(parts[0], strict: true) else { return .invalid }

        let summary = token.displayAmount
        if isGroup && !confirmedPublic { return .needsPublicConfirm(summary: summary) }
        return .send(token: token, summary: summary)
    }

    /// __CASHU_SEND_READY_2026_11_09__ What the Send-bitcoin sheet makes of
    /// whatever was pasted into it. The SAME rules as `/pay` — it literally is
    /// `/pay <text>` — so the sheet can never accept a string the command would
    /// refuse. A 1:1 decision: the sheet states the group risk itself.
    static func parsePasted(_ text: String) -> Outcome {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return .usage }
        return parse(prefix + " " + t, isGroup: false)
    }

    /// The first Cashu token in `text` (a clipboard holding a sentence around
    /// the token, or a `cashu:` link), or nil.
    static func firstToken(in text: String) -> String? {
        CashuTokens.find(in: text).first?.token.raw
    }

    /// The line to show when the transport refused a token. A refusal for
    /// length gets its own words: the generic "shorten it" is meaningless for a
    /// string that IS the money and cannot be edited.
    static func refusalMessage(for outcome: MessageSendOutcome) -> String? {
        if outcome == .tooLong {
            return NSLocalizedString("cashu_too_long", comment: "Token too long for the current transport")
        }
        return outcome.userFacingMessage
    }

    /// The localized line the composer shows for an outcome that did not send.
    /// Returned rather than displayed here so this file stays pure.
    static func message(for outcome: Outcome) -> String? {
        switch outcome {
        case .usage:
            return NSLocalizedString("cashu_pay_usage", comment: "How to use /pay")
        case .notAToken:
            return NSLocalizedString("cashu_pay_not_token", comment: "/pay argument is not a cashu token")
        case .invalid:
            return NSLocalizedString("cashu_pay_invalid", comment: "/pay token does not decode")
        case .needsPublicConfirm(let summary):
            return String(format: NSLocalizedString("cashu_pay_public_confirm",
                                                    comment: "Confirmation gate before a group send"), summary)
        case .send(_, let summary):
            return String(format: NSLocalizedString("cashu_pay_sent",
                                                    comment: "Confirmation after sending"), summary)
        }
    }
}
