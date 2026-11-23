//
//  OshiLog.swift
//  OSHI
//
//  Centralized logger for the legacy OSHI 1.x codebase. Phase 1 of the
//  OSHI_2 migration plan: every print() call routes through here.
//
//  Why a shim instead of plain os.Logger?
//  --------------------------------------
//  os.Logger.info(_:) takes an `OSLogMessage` whose interpolation is
//  evaluated as an `@escaping @autoclosure`. That escape forces every
//  bare property reference inside an interpolation to be qualified with
//  `self.` (Swift 5.9 strict-concurrency rule). Mechanically swapping
//  3000+ print() calls would have required editing every closure body to
//  add `self.` — a cross-cutting change that's easy to get wrong.
//
//  This shim accepts a plain `String`, so the interpolation runs as
//  ordinary string formatting before it ever reaches the Logger. The
//  trade-off: structured privacy markers (`\(value, privacy: .private)`)
//  aren't available at the call site for now. Phase 2 reintroduces them
//  per-module as part of the OshiCrypto / OshiCalls extractions.
//
//  Capture for testers:
//      log show --predicate 'subsystem == "app.oshi"' --info --last 1h
//

import Foundation
import os.log

public struct OshiLogger {
    private let logger: Logger

    fileprivate init(category: String) {
        self.logger = Logger(subsystem: "app.oshi", category: category)
    }

    // MARK: Default = PRIVATE
    //
    // Messages are logged with `privacy: .private`, so OSLog redacts the dynamic
    // content to `<private>` in release / on-device sysdiagnose while still
    // showing it to a developer attached in Xcode. This restores OSLog's
    // redaction, which the previous `.public` default defeated for every line —
    // message bodies, prompts, keys and ids all landed in the shipping log.
    //
    // Use the `*Public(...)` variants ONLY for values known to carry no user
    // content or secrets (fixed strings, counts, coarse states).

    public func debug(_ message: String) {
        logger.debug("\(message, privacy: .private)")
    }

    public func info(_ message: String) {
        logger.info("\(message, privacy: .private)")
    }

    public func notice(_ message: String) {
        logger.notice("\(message, privacy: .private)")
    }

    public func warning(_ message: String) {
        logger.warning("\(message, privacy: .private)")
    }

    public func error(_ message: String) {
        logger.error("\(message, privacy: .private)")
    }

    public func fault(_ message: String) {
        logger.fault("\(message, privacy: .private)")
    }

    // MARK: Explicit public — non-sensitive fields only

    public func debugPublic(_ message: String) {
        logger.debug("\(message, privacy: .public)")
    }

    public func infoPublic(_ message: String) {
        logger.info("\(message, privacy: .public)")
    }

    public func noticePublic(_ message: String) {
        logger.notice("\(message, privacy: .public)")
    }

    public func warningPublic(_ message: String) {
        logger.warning("\(message, privacy: .public)")
    }

    public func errorPublic(_ message: String) {
        logger.error("\(message, privacy: .public)")
    }

    public func faultPublic(_ message: String) {
        logger.fault("\(message, privacy: .public)")
    }
}

public enum OshiLog {
    public static let app       = OshiLogger(category: "app")
    public static let crypto    = OshiLogger(category: "crypto")
    public static let mesh      = OshiLogger(category: "mesh")
    public static let call      = OshiLogger(category: "call")
    public static let messaging = OshiLogger(category: "messaging")
    public static let storage   = OshiLogger(category: "storage")
    public static let ai        = OshiLogger(category: "ai")
    public static let maps      = OshiLogger(category: "maps")
    public static let web       = OshiLogger(category: "web")
    public static let ui        = OshiLogger(category: "ui")
}
