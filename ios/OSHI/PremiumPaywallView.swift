//
//  PremiumPaywallView.swift
//  OSHI
//
//  The upgrade sheet.
//
//  It sells storage, not "premium". OSHI Premium is 2 GB of mail, 20 GB of
//  drive and 1000 sends a month — concrete numbers against concrete free ones —
//  because a paywall that lists adjectives asks the user to guess what they are
//  buying.
//
//  Prices come from StoreKit (`product.displayPrice`), never from a constant:
//  they are already in the user's currency, already carry the right tax
//  treatment, and hard-coding "$2.99" in an app sold in 175 territories is how
//  you show a French customer a dollar sign.
//

import SwiftUI
import StoreKit

struct PremiumPaywallView: View {
    @ObservedObject var mail: OSHIMailClient
    @StateObject private var premium = PremiumManager.shared
    @Environment(\.dismiss) private var dismiss

    @State private var selected: String = PremiumManager.ProductID.yearly

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    header
                    comparison
                    plans
                    purchaseButton
                    footer
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .navigationTitle("premium.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.close".localized) { dismiss() }
                }
            }
            .task { await premium.loadProducts() }
            .onChange(of: mail.account?.tier) { _, tier in
                // The server is what decides this, so the sheet closes on the
                // server's word rather than on StoreKit's.
                if tier == "premium" { dismiss() }
            }
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: 10) {
            Image(systemName: "externaldrive.badge.person.crop")
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(Theme.brandGradient)
                .padding(.top, 12)
            Text("premium.headline".localized)
                .font(.title2.weight(.semibold))
                .multilineTextAlignment(.center)
            Text("premium.subhead".localized)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    // MARK: What you actually get

    private var comparison: some View {
        VStack(spacing: 0) {
            row(title: "premium.row.mail".localized, free: "150 MB", paid: "2 GB")
            Divider()
            row(title: "premium.row.drive".localized, free: "1 GB", paid: "20 GB")
            Divider()
            row(title: "premium.row.sends".localized, free: "150", paid: "1000")
        }
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemBackground)))
    }

    private func row(title: String, free: String, paid: String) -> some View {
        HStack {
            Text(title).font(.subheadline)
            Spacer()
            Text(free)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.tertiary)
                .strikethrough()
            Image(systemName: "arrow.right")
                .font(.caption2)
                .foregroundStyle(.tertiary)
            Text(paid)
                .font(.subheadline.monospacedDigit().weight(.semibold))
                .foregroundStyle(Theme.brand)
                .frame(minWidth: 54, alignment: .trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    // MARK: Plans

    @ViewBuilder
    private var plans: some View {
        if premium.products.isEmpty {
            // No prices means no honest way to ask for money, so the sheet shows
            // why instead of an inert button.
            VStack(spacing: 8) {
                if case .failed(let message) = premium.phase {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, minHeight: 120)
        } else {
            VStack(spacing: 10) {
                ForEach(premium.products, id: \.id) { product in
                    planCard(product)
                }
            }
        }
    }

    private func planCard(_ product: Product) -> some View {
        let isSelected = selected == product.id
        let isYearly = product.id == PremiumManager.ProductID.yearly
        return Button {
            selected = product.id
        } label: {
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(isSelected ? Theme.brand : Color.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(isYearly ? "premium.plan.yearly".localized : "premium.plan.monthly".localized)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                    Text(product.displayPrice)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if isYearly, let saving = premium.yearlySavingPercent {
                    Text(String(format: "premium.saving".localized, saving))
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(Theme.brand.opacity(0.15)))
                        .foregroundStyle(Theme.brand)
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isSelected ? Theme.brand : Color.secondary.opacity(0.25),
                            lineWidth: isSelected ? 2 : 1)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: Buy

    private var purchaseButton: some View {
        VStack(spacing: 10) {
            Button {
                guard let product = premium.products.first(where: { $0.id == selected }) else { return }
                Task { await premium.purchase(product) }
            } label: {
                // SwiftUI.Group spelled out: OSHI has its own `Group` type for
                // group chats, and the bare name resolves to that one here.
                SwiftUI.Group {
                    if isWorking {
                        ProgressView().tint(.white)
                    } else {
                        Text("premium.cta".localized).font(.headline)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.brand)
            .disabled(premium.products.isEmpty || isWorking)

            if case .failed(let message) = premium.phase {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }

            // Tinted explicitly. The app's AccentColor asset is pure WHITE in
            // both light and dark appearances, so anything that inherits the
            // accent — a plain Button label, a Link — renders white on this
            // white sheet and simply is not there. Apple requires a visible
            // Restore control for auto-renewable subscriptions (3.1.2), so
            // inheriting the accent here is a rejection, not a style choice.
            Button("premium.restore".localized) {
                Task { await premium.restore() }
            }
            .font(.footnote)
            .tint(Theme.brand)
            .foregroundStyle(Theme.brand)
            .disabled(isWorking)
        }
    }

    private var isWorking: Bool {
        switch premium.phase {
        case .purchasing, .redeeming, .loadingProducts: return true
        default: return false
        }
    }

    // MARK: Small print

    private var footer: some View {
        VStack(spacing: 6) {
            // Apple requires auto-renewal terms to be visible at the point of
            // purchase, not only in the App Store listing.
            Text("premium.terms".localized)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            // Same white-accent trap as the Restore button above, and the
            // stakes are the same: 3.1.2(c) wants the EULA reachable from the
            // point of purchase, and a link nobody can see does not count.
            HStack(spacing: 14) {
                Link("premium.link.terms".localized,
                     destination: URL(string: "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/")!)
                Link("premium.link.privacy".localized,
                     destination: URL(string: "https://oshi-messenger.com/privacy.html")!)
            }
            .font(.caption2)
            .tint(Theme.brand)
            .foregroundStyle(Theme.brand)
        }
    }
}
