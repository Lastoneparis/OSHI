//
//  PremiumManager.swift
//  OSHI
//
//  StoreKit 2 purchasing for OSHI Premium.
//
//  The rule this file is built around: StoreKit tells us what the user BOUGHT,
//  the server decides what the user GETS. Those are different questions. A
//  device can be jailbroken, a StoreKit response can be faked, and
//  `Transaction.currentEntitlements` is ultimately something the OS on that
//  device asserts. So nothing here ever sets a tier — it collects Apple's signed
//  transaction and posts it to the server, which verifies Apple's signature
//  against a pinned root and records the grant. `OSHIMailClient.account.tier` is
//  the only thing the UI trusts.
//
//  That also means an unfinished transaction is a feature. A purchase is only
//  finished once the server has acknowledged it, so if the network drops between
//  paying Apple and telling our server, StoreKit re-delivers the transaction on
//  the next launch and the grant completes then. Finishing early would lose the
//  purchase silently — the user would be charged and get nothing.
//

import Foundation
import StoreKit

@MainActor
final class PremiumManager: ObservableObject {

    static let shared = PremiumManager()

    enum ProductID {
        static let monthly = "oshi.premium.monthly"
        static let yearly = "oshi.premium.yearly"
        static let all = [monthly, yearly]
    }

    /// What the paywall is doing right now. `purchasing` and `redeeming` are
    /// separate because they fail differently: the first is Apple's problem and
    /// the user may be mid-sheet, the second is ours and is worth retrying.
    enum Phase: Equatable {
        case idle
        case loadingProducts
        case purchasing(String)
        case redeeming
        case failed(String)
    }

    @Published private(set) var products: [Product] = []
    @Published private(set) var phase: Phase = .idle
    /// Mirrors what StoreKit believes this Apple ID owns. Shown as "you already
    /// have a subscription" — never as an entitlement in its own right.
    @Published private(set) var hasStoreKitEntitlement = false

    private var updatesTask: Task<Void, Never>?
    private weak var mail: OSHIMailClient?

    private init() {}

    /// Starts the transaction listener. Must be called once, early, and before
    /// any purchase: renewals, refunds, Ask-to-Buy approvals and purchases made
    /// on another device all arrive through `Transaction.updates` rather than
    /// through the call that started them.
    func start(mail: OSHIMailClient) {
        self.mail = mail
        guard updatesTask == nil else { return }
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                await self?.handle(result, source: "updates")
            }
        }
        Task { await refreshEntitlement() }
    }

    deinit { updatesTask?.cancel() }

    // MARK: - Products

    func loadProducts() async {
        guard products.isEmpty else { return }
        phase = .loadingProducts
        do {
            let fetched = try await Product.products(for: ProductID.all)
            // Cheapest first so the monthly price anchors the yearly saving.
            products = fetched.sorted { $0.price < $1.price }
            phase = .idle
            if products.isEmpty {
                // Products missing usually means the paid-apps agreement or the
                // product's own availability is not in place yet — not something
                // the user can act on, so say so plainly rather than "try again".
                phase = .failed("premium.error.noProducts".localized)
            }
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    var monthly: Product? { products.first { $0.id == ProductID.monthly } }
    var yearly: Product? { products.first { $0.id == ProductID.yearly } }

    /// What the yearly plan saves against paying monthly, as a whole percent.
    /// Returns nil rather than 0 when it cannot be computed, so the badge is
    /// simply absent instead of claiming "save 0%".
    var yearlySavingPercent: Int? {
        guard let m = monthly?.price, let y = yearly?.price, m > 0 else { return nil }
        let twelve = m * 12
        guard twelve > y else { return nil }
        let ratio = (twelve - y) / twelve
        return Int((ratio as NSDecimalNumber).doubleValue * 100.0)
    }

    // MARK: - Purchase

    func purchase(_ product: Product) async {
        phase = .purchasing(product.id)
        do {
            let result = try await product.purchase()
            switch result {
            case .success(let verification):
                await handle(verification, source: "purchase")
            case .userCancelled:
                phase = .idle
            case .pending:
                // Ask-to-Buy: a parent still has to approve. The transaction will
                // arrive through Transaction.updates if and when they do.
                phase = .failed("premium.error.pending".localized)
            @unknown default:
                phase = .idle
            }
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    /// "Restore Purchases". `AppStore.sync()` re-authenticates and refreshes the
    /// device's transactions; the entitlement sweep then re-sends whatever it
    /// finds. Apple requires this button to exist for auto-renewable products.
    func restore() async {
        phase = .redeeming
        do { try await AppStore.sync() } catch {
            // A cancelled sign-in sheet throws here. The sweep below can still
            // succeed from what is already on device, so this is not fatal.
        }
        await refreshEntitlement()
        phase = .idle
    }

    /// Re-sends every current entitlement to the server. Safe to call often: the
    /// server binds a receipt to the first mailbox that claims it and treats a
    /// repeat from that same mailbox as a renewal, not a new grant.
    func refreshEntitlement() async {
        var found = false
        for await result in Transaction.currentEntitlements {
            guard case .verified(let tx) = result,
                  ProductID.all.contains(tx.productID) else { continue }
            found = true
            await send(result)
        }
        hasStoreKitEntitlement = found
    }

    // MARK: - Redeem

    private func handle(_ result: VerificationResult<Transaction>, source: String) async {
        // Deliberately NOT gated on `case .verified`. Local verification uses the
        // device's own copy of Apple's certificates, and this device is the one
        // thing we cannot trust. The server re-verifies the same signature
        // against a pinned root, so forwarding an "unverified" transaction costs
        // nothing and avoids refusing a real purchase because of a clock skew or
        // an OS bug on the customer's phone.
        await send(result)
        if case .verified(let tx) = result {
            hasStoreKitEntitlement = tx.revocationDate == nil
        }
    }

    private func send(_ result: VerificationResult<Transaction>) async {
        guard let mail else { return }
        guard mail.account != nil else {
            // No mailbox means nothing to attach the subscription to. Leave the
            // transaction UNFINISHED so it is redelivered once they register.
            phase = .failed("premium.error.needsMailbox".localized)
            return
        }
        phase = .redeeming
        do {
            try await mail.redeemPremium(signedTransaction: result.jwsRepresentation)
            // Only now: the server has the grant, so losing this transaction is
            // no longer a lost purchase.
            if case .verified(let tx) = result { await tx.finish() }
            phase = .idle
        } catch let OSHIMailError.server(status, code) where status == 409 {
            // This receipt already belongs to a different mailbox. Retrying will
            // never help, so finish it — otherwise it is redelivered on every
            // launch forever and the error reappears each time.
            if case .verified(let tx) = result { await tx.finish() }
            phase = .failed("premium.error.alreadyClaimed".localized)
            _ = code
        } catch {
            // Anything else is transient (offline, 5xx). Leave it unfinished so
            // StoreKit redelivers it and the grant lands on a later launch.
            phase = .failed(error.localizedDescription)
        }
    }
}
