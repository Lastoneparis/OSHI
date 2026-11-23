//
//  CallRatingView.swift
//  OSHI
//
//  __CALL_RATING_2026_09_22__
//
//  The post-call sheet. One tap is a complete answer: choosing a star sends it
//  and closes, the way WhatsApp's does. Everything below the stars — the canned
//  reasons, the note field — only appears for a poor rating, because that is the
//  only case where more detail changes what anyone can fix.
//

import SwiftUI

struct CallRatingView: View {

    let item: PendingCallRating
    @ObservedObject var manager: CallRatingManager

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var rating = 0
    @State private var reasons: Set<CallRatingReason> = []
    @State private var comment = ""
    @State private var appear = false
    @FocusState private var commentFocused: Bool

    /// A poor call is where the detail is worth asking for. Three and below.
    private var wantsDetail: Bool { (1...3).contains(rating) }

    var body: some View {
        ScrollView {
            VStack(spacing: Theme.Space.lg) {

                Image(systemName: item.wasVideo ? "video.fill" : "phone.fill")
                    .font(.scaled(38))
                    .foregroundStyle(Theme.textSecondary)
                    .scaleEffect(appear || reduceMotion ? 1 : 0.6)
                    .padding(.top, Theme.Space.xl)
                    .accessibilityHidden(true)

                VStack(spacing: Theme.Space.sm) {
                    Text("call.rating.title".localized)
                        .font(.system(.title2, design: .rounded).weight(.bold))
                        .foregroundStyle(Theme.textPrimary)
                        .multilineTextAlignment(.center)

                    Text("call.rating.subtitle".localized)
                        .font(Theme.Font.body)
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.horizontal, Theme.Space.xl)

                stars
                    .padding(.top, Theme.Space.xs)

                if wantsDetail {
                    detail
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .move(edge: .top)))
                }

                actions
            }
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.surface.ignoresSafeArea())
        .presentationDetents(wantsDetail ? [.large] : [.height(380), .medium])
        .presentationDragIndicator(.visible)
        .onAppear {
            withAnimation(reduceMotion ? nil : Theme.Motion.bouncy) { appear = true }
        }
    }

    // MARK: Stars

    private var stars: some View {
        HStack(spacing: Theme.Space.sm) {
            ForEach(1...5, id: \.self) { value in
                Button {
                    Haptics.tap()
                    withAnimation(reduceMotion ? nil : Theme.Motion.bouncy) {
                        rating = value
                    }
                    // A good call needs nothing more from the person — take the
                    // answer and get out of the way.
                    if value >= 4 { send() }
                } label: {
                    Image(systemName: value <= rating ? "star.fill" : "star")
                        .font(.scaled(30))
                        .foregroundStyle(value <= rating ? Theme.warning : Theme.textSecondary.opacity(0.45))
                        .scaleEffect(value == rating && !reduceMotion ? 1.18 : 1)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(format: "call.rating.star.accessibility".localized, value))
                .accessibilityAddTraits(value <= rating ? [.isSelected] : [])
            }
        }
        .accessibilityElement(children: .contain)
    }

    // MARK: Detail, only for a poor rating

    private var detail: some View {
        VStack(alignment: .leading, spacing: Theme.Space.md) {

            Text("call.rating.what_happened".localized)
                .font(.system(.headline, design: .rounded))
                .foregroundStyle(Theme.textPrimary)

            // A plain wrapping flow: chips are short and this keeps every one
            // of the 35 translations laid out correctly without measuring.
            VStack(alignment: .leading, spacing: Theme.Space.sm) {
                ForEach(CallRatingReason.allCases) { reason in
                    Button {
                        Haptics.tap()
                        if reasons.contains(reason) { reasons.remove(reason) } else { reasons.insert(reason) }
                    } label: {
                        HStack(spacing: Theme.Space.sm) {
                            Image(systemName: reasons.contains(reason) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(reasons.contains(reason) ? Theme.brand : Theme.textSecondary.opacity(0.5))
                            Image(systemName: reason.symbolName)
                                .foregroundStyle(Theme.textSecondary)
                                .frame(width: 22)
                                .accessibilityHidden(true)
                            Text(reason.localizedTitle)
                                .font(Theme.Font.body)
                                .foregroundStyle(Theme.textPrimary)
                                .multilineTextAlignment(.leading)
                                .fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, Theme.Space.sm)
                        .padding(.horizontal, Theme.Space.md)
                        .background(
                            RoundedRectangle(cornerRadius: Theme.Radius.md, style: .continuous)
                                .fill(reasons.contains(reason) ? Theme.brand.opacity(0.10) : Theme.textSecondary.opacity(0.06))
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(reasons.contains(reason) ? [.isSelected] : [])
                }
            }

            TextField("call.rating.note.placeholder".localized, text: $comment, axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.plain)
                .font(Theme.Font.body)
                .padding(Theme.Space.md)
                .background(
                    RoundedRectangle(cornerRadius: Theme.Radius.md, style: .continuous)
                        .fill(Theme.textSecondary.opacity(0.06))
                )
                .focused($commentFocused)

            Text("call.rating.privacy".localized)
                .font(Theme.Font.caption)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Theme.Space.xl)
    }

    // MARK: Actions

    private var actions: some View {
        VStack(spacing: Theme.Space.md) {
            if wantsDetail {
                Button {
                    Haptics.success()
                    send()
                } label: {
                    Text("call.rating.send".localized)
                        .font(.system(.headline, design: .rounded))
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, Theme.Space.md)
                        .background(Theme.brandGradient)
                        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.md, style: .continuous))
                }
                .pressable()
            }

            Button {
                Haptics.tap()
                manager.dismiss()
                dismiss()
            } label: {
                Text("call.rating.not_now".localized)
                    .font(.system(.headline, design: .rounded))
                    .foregroundStyle(Theme.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Theme.Space.md)
            }
        }
        .padding(.horizontal, Theme.Space.xl)
        .padding(.bottom, Theme.Space.xl)
    }

    private func send() {
        commentFocused = false
        manager.submit(rating: rating, reasons: reasons, comment: comment)
        dismiss()
    }
}

// MARK: - Root attachment

private struct CallRatingModifier: ViewModifier {
    @ObservedObject private var manager = CallRatingManager.shared

    func body(content: Content) -> some View {
        content.sheet(item: $manager.pending) { item in
            CallRatingView(item: item, manager: manager)
        }
    }
}

extension View {
    /// Attach once near the app root. Presents the post-call quality sheet
    /// whenever `CallRatingManager` decides a finished call is worth asking about.
    func callRatingPrompt() -> some View {
        modifier(CallRatingModifier())
    }
}
