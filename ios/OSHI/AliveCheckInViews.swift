import SwiftUI

/// __ALIVE_CHECK_IN_2026_11_04__ Everything the liveness check-in draws.
///
/// ## The colour rule
///
/// Green and red are ACCENTS — a dot, a hairline, a tint on one word — never a
/// filled panel. Two reasons, and both are about the same failure:
///
///  * A red card across the top of a conversation reads as an alarm, and this
///    feature must never claim an emergency it cannot know about (see
///    ``AliveCheckInPolicy/overdueIsNotProof``).
///  * Colour alone never carries the meaning. Every state also changes the
///    SYMBOL and the WORDS, because a red dot and a green dot are the same dot
///    to a red-green colour-blind reader, and because VoiceOver reads neither.
///
/// ## The countdown ticks in the view, not in a stored number
///
/// A `TimelineView` re-reads the clock and the displayed value is recomputed
/// from the two stored timestamps on each tick, so a view that was off screen
/// for six hours comes back correct rather than six hours behind. A countdown
/// held in state and decremented would drift for exactly as long as the app was
/// suspended, which is most of the time for this feature.
///
/// Android: `ui/components/AliveCheckInUi.kt`.
enum AliveCheckInPalette {
    /// Not `Color.red`: the system red is tuned to shout, which is wrong for a
    /// state whose honest name is "no answer yet". A desaturated amber-red reads
    /// as attention rather than danger, and clears 4.5:1 on both appearances.
    static let overdue = Color(red: 0.85, green: 0.33, blue: 0.31)
    static let confirmed = Color(red: 0.20, green: 0.70, blue: 0.48)

    static func accent(_ status: AliveCheckInPolicy.Status) -> Color {
        switch status {
        case .overdue: return overdue
        case .confirmed: return confirmed
        case .neutral: return .secondary
        }
    }

    static func symbol(_ status: AliveCheckInPolicy.Status) -> String {
        status == .confirmed ? "heart.fill" : "heart"
    }

    static func a11yLabel(_ status: AliveCheckInPolicy.Status) -> String {
        switch status {
        case .confirmed: return "alive.a11y.confirmed".localized
        case .overdue: return "alive.a11y.overdue".localized
        case .neutral: return "alive.a11y.neutral".localized
        }
    }
}

/// The strip that sits under the conversation's header once a check-in exists
/// in either direction.
///
/// Absent entirely when nothing is configured — the way in for that case is the
/// conversation's overflow menu. A permanent bar in every one of a user's
/// conversations, for an opt-in feature most of them will never use, is the
/// clutter the design brief rules out.
struct AliveCheckInBar: View {
    let peerName: String
    let entry: AliveCheckInStore.Entry
    let onConfirm: () -> Void
    let onOpenSettings: () -> Void

    var body: some View {
        if entry.isIdle {
            EmptyView()
        } else {
            // A one-minute cadence is enough for a countdown whose smallest
            // displayed unit is the minute, and it is the cadence that does not
            // wake the CPU for a number that has not changed.
            TimelineView(.periodic(from: .now, by: 60)) { context in
                content(now: context.date)
            }
        }
    }

    @ViewBuilder
    private func content(now: Date) -> some View {
        let status = entry.rowStatus(now: now)
        let accent = AliveCheckInPalette.accent(status)
        HStack(spacing: 10) {
            Image(systemName: AliveCheckInPalette.symbol(status))
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(accent)
                .frame(width: 22, height: 22)
                .background(accent.opacity(0.14), in: Circle())
                .accessibilityLabel(AliveCheckInPalette.a11yLabel(status))

            VStack(alignment: .leading, spacing: 1) {
                // Their lane first: it is the half the reader cannot act on, and
                // therefore the half they came to check.
                if entry.theirsEnabled {
                    lane(who: peerName,
                         text: AliveCheckInPolicy.laneText(
                            lastConfirmedAt: entry.theirsLastConfirmedAt,
                            intervalSeconds: entry.theirsIntervalSeconds,
                            now: now),
                         emphasised: entry.theirStatus(now: now) == .overdue,
                         accent: accent)
                }
                if entry.mineEnabled {
                    lane(who: "alive.lane.you".localized,
                         text: AliveCheckInPolicy.laneText(
                            lastConfirmedAt: entry.mineLastConfirmedAt,
                            intervalSeconds: entry.mineIntervalSeconds,
                            now: now),
                         emphasised: entry.myStatus(now: now) == .overdue,
                         accent: accent)
                }
            }

            Spacer(minLength: 4)

            Button(action: onConfirm) {
                HStack(spacing: 5) {
                    Image(systemName: "heart.fill").font(.system(size: 11))
                    Text("alive.action.im_alive".localized)
                        .font(.caption).fontWeight(.semibold)
                        .lineLimit(1)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)

            Button(action: onOpenSettings) {
                Image(systemName: "slider.horizontal.3").font(.system(size: 13))
            }
            .buttonStyle(.plain)
            .foregroundColor(.secondary)
            // 44pt: a bare glyph is well under the touch-target floor, and this
            // one sits beside a button people are aiming at.
            .frame(width: 44, height: 44)
            .accessibilityLabel("alive.bar.open_settings".localized)
        }
        .padding(.leading, 12)
        .padding(.trailing, 2)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.secondary.opacity(0.10))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(accent.opacity(0.35), lineWidth: 1)
                )
        )
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpenSettings)
        .animation(.easeOut(duration: 0.26), value: status)
    }

    @ViewBuilder
    private func lane(who: String, text: String, emphasised: Bool, accent: Color) -> some View {
        HStack(spacing: 6) {
            Text(who)
                .font(.caption2).fontWeight(.semibold)
                .lineLimit(1)
                .frame(maxWidth: 120, alignment: .leading)
            Text(text)
                .font(.caption2)
                // The WORDS already say which state this is; the colour only
                // reinforces it, and only on the half that is overdue.
                .foregroundColor(emphasised ? accent : .secondary)
                .fontWeight(emphasised ? .medium : .regular)
                .lineLimit(1)
        }
    }
}

/// The marker a CONVERSATION ROW carries, plus its countdown.
///
/// Deliberately not a coloured background or a badge. The row already spends its
/// colour budget on the unread pill, and a check-in state that outranked unread
/// would reorder the reader's attention away from the actual messages.
struct AliveRowMarker: View {
    let entry: AliveCheckInStore.Entry?
    /// Whether to print the countdown as well as the symbol. The name line has
    /// room for a glyph only; the preview line has room for both.
    var showCountdown: Bool = false

    var body: some View {
        if let entry, !entry.isIdle {
            TimelineView(.periodic(from: .now, by: 60)) { context in
                let status = entry.rowStatus(now: context.date)
                if status == .neutral {
                    EmptyView()
                } else {
                    HStack(spacing: 4) {
                        Image(systemName: AliveCheckInPalette.symbol(status))
                            .font(.system(size: 10))
                            .foregroundColor(AliveCheckInPalette.accent(status))
                            .accessibilityLabel(AliveCheckInPalette.a11yLabel(status))
                        if showCountdown, let due = entry.rowDueAt() {
                            Text(countdown(due: due, now: context.date))
                                .font(.system(size: 10))
                                .foregroundColor(status == .overdue
                                                 ? AliveCheckInPalette.overdue : .secondary)
                                .lineLimit(1)
                        }
                    }
                }
            }
        } else {
            EmptyView()
        }
    }

    private func countdown(due: Date, now: Date) -> String {
        let left = due.timeIntervalSince(now)
        if left >= 0 {
            return String(format: "alive.state.due_in".localized, AliveCheckInPolicy.text(left))
        }
        return String(format: "alive.state.overdue_by".localized, AliveCheckInPolicy.text(-left))
    }
}

/// The settings sheet: the switch, the interval, the other side's state, and the
/// two sentences that keep the feature honest.
struct AliveCheckInSheet: View {
    let peerName: String
    let entry: AliveCheckInStore.Entry
    let onConfirm: () -> Void
    let onEnable: (Int) -> Void
    let onDisable: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        NavigationStack {
            // A Form, so the body scrolls. It grows with the state — the
            // interval rows and the stepper only exist while the switch is on —
            // and at its tallest it does not fit a short or landscape screen.
            // Without scrolling, the two notes at the bottom are exactly what
            // gets stranded, and they are the ones that keep the feature honest
            // about what it can promise. (Android's equivalent was caught by
            // `GroupChatSheetReachabilityTest` doing precisely this.)
            Form {
                Section {
                    Text(String(format: "alive.sheet.explainer".localized, peerName))
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }

                Section {
                    Toggle(String(format: "alive.sheet.mine_switch".localized, peerName),
                           isOn: Binding(
                            get: { entry.mineEnabled },
                            set: { on in on ? onEnable(entry.mineIntervalSeconds) : onDisable() }))

                    if entry.mineEnabled {
                        Picker("alive.sheet.interval".localized,
                               selection: Binding(
                                get: { entry.mineIntervalSeconds },
                                set: { onEnable($0) })) {
                            Text("alive.interval.24h".localized).tag(AliveCheckInInterval.every24h)
                            Text("alive.interval.48h".localized).tag(AliveCheckInInterval.every48h)
                            Text("alive.interval.72h".localized).tag(AliveCheckInInterval.every72h)
                            // Custom appears as a tag only when it IS the current
                            // value, so a user who set "every 8 hours" sees that
                            // number rather than an unlabelled "Custom" they have
                            // to tap to recall. A Picker with no tag matching the
                            // selection shows nothing at all.
                            if !AliveCheckInInterval.isPreset(entry.mineIntervalSeconds) {
                                Text(String(format: "alive.interval.custom_hours".localized,
                                            entry.mineIntervalSeconds / AliveCheckInInterval.hour))
                                    .tag(entry.mineIntervalSeconds)
                            }
                        }

                        // A stepper rather than a text field: a keyboard here
                        // invites "0", "999" and "", each of which has to be
                        // rejected with a message, and the clamp would silently
                        // rewrite what the user typed. A stepper cannot express
                        // an invalid interval at all.
                        Stepper(
                            String(format: "alive.interval.custom_hours".localized,
                                   entry.mineIntervalSeconds / AliveCheckInInterval.hour),
                            onIncrement: {
                                onEnable(AliveCheckInInterval.clamp(
                                    entry.mineIntervalSeconds + AliveCheckInInterval.hour))
                            },
                            onDecrement: {
                                onEnable(AliveCheckInInterval.clamp(
                                    entry.mineIntervalSeconds - AliveCheckInInterval.hour))
                            })

                        Button(action: onConfirm) {
                            HStack {
                                Image(systemName: "heart.fill")
                                Text("alive.action.im_alive".localized).fontWeight(.semibold)
                            }
                        }
                        Button("alive.sheet.stop".localized, role: .destructive, action: onDisable)
                    }
                }

                // Their half: read-only. We cannot set someone else's schedule.
                Section(String(format: "alive.sheet.their_section".localized, peerName)) {
                    if entry.theirsEnabled {
                        TimelineView(.periodic(from: .now, by: 60)) { context in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(AliveCheckInPolicy.laneText(
                                        lastConfirmedAt: entry.theirsLastConfirmedAt,
                                        intervalSeconds: entry.theirsIntervalSeconds,
                                        now: context.date))
                                Text(String(format: "alive.interval.every".localized,
                                            AliveCheckInPolicy.text(
                                                TimeInterval(entry.theirsIntervalSeconds))))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    } else {
                        Text(String(format: "alive.sheet.their_none".localized, peerName))
                            .foregroundColor(.secondary)
                    }
                }

                Section {
                    Text("alive.note.no_location".localized)
                        .font(.caption).foregroundColor(.secondary)
                    Text("alive.note.delivery".localized)
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            .navigationTitle("alive.title".localized)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done".localized, action: onDismiss)
                }
            }
        }
    }
}

/// Presents ``AliveCheckInSheet``, as a modifier rather than as a `.sheet` on
/// the conversation's main view chain.
///
/// Not a style preference. Attached inline, that one `.sheet` was enough to tip
/// `ChatView.chatViewWithNavigation` past the Swift type-checker's limit —
/// *"unable to type-check this expression in reasonable time"* — because the
/// chain is already at the edge, as `MessageBubble`'s own header records. A
/// `ViewModifier` collapses to a single opaque type, so the chain's cost does
/// not grow with what the sheet contains.
struct AliveCheckInSheetModifier: ViewModifier {
    @Binding var isPresented: Bool
    let peerName: String
    let entry: AliveCheckInStore.Entry
    let onConfirm: () -> Void
    let onEnable: (Int) -> Void
    let onDisable: () -> Void

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPresented) {
            AliveCheckInSheet(
                peerName: peerName,
                entry: entry,
                onConfirm: {
                    onConfirm()
                    isPresented = false
                },
                onEnable: onEnable,
                onDisable: onDisable,
                onDismiss: { isPresented = false })
        }
    }
}
