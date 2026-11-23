//
//  PollViews.swift
//  OSHI
//
//  The two surfaces of a poll: the sheet that composes one, and the bubble
//  that shows it in a thread. Both are used unchanged by the 1:1 chat and by
//  a group — a poll does not behave differently in a group, it just has more
//  voters.
//
//  Layout notes that are not decoration
//  -----------------------------------
//  * The option label is `fixedSize(horizontal: false, vertical: true)` and
//    the percentage carries `layoutPriority`. Without both, a long option next
//    to a short number collapses into one letter per line — the trap AX5 found
//    on nine screens of another app in this repo.
//  * Nothing is positioned with left/right. `ZStack(alignment: .leading)` and
//    `HStack` mirror themselves in Arabic, Persian and Hebrew, which are three
//    of the 34 languages this ships in; an `.offset` would not.
//  * The bar is drawn against the number of VOTERS, not the number of picks,
//    so in a multiple-answer poll the bars can add up past 100 % and each one
//    still reads as "this share of people chose it".
//
//  Android: `ui/components/PollBubble.kt`, `ui/components/PollComposerSheet.kt`.
//

import SwiftUI

// MARK: - Composing

struct PollComposerSheet: View {

    /// Called with a ready-to-send poll. The caller owns sending it, because
    /// only it knows whether this is a 1:1 recipient or a group.
    let onSend: (PollMessage) -> Void
    let creatorPublicKey: String

    @Environment(\.dismiss) private var dismiss

    @State private var question: String = ""
    @State private var options: [String] = ["", ""]
    @State private var allowsMultipleAnswers = false
    @State private var isAnonymous = false
    @State private var duration: PollDuration = .never
    @FocusState private var focused: Field?

    private enum Field: Hashable {
        case question
        case option(Int)
    }

    enum PollDuration: CaseIterable, Identifiable {
        case never, oneHour, sixHours, oneDay, threeDays, sevenDays

        var id: Self { self }

        /// Fixed labels rather than "%d hours": the app has no plural engine
        /// (no .stringsdict anywhere), and "1 hours" in 34 languages is a
        /// worse outcome than six more keys.
        var label: String {
            switch self {
            case .never:     return "poll.duration.never".localized
            case .oneHour:   return "poll.duration.1h".localized
            case .sixHours:  return "poll.duration.6h".localized
            case .oneDay:    return "poll.duration.24h".localized
            case .threeDays: return "poll.duration.3d".localized
            case .sevenDays: return "poll.duration.7d".localized
            }
        }

        var seconds: TimeInterval? {
            switch self {
            case .never:     return nil
            case .oneHour:   return 3600
            case .sixHours:  return 6 * 3600
            case .oneDay:    return 24 * 3600
            case .threeDays: return 3 * 24 * 3600
            case .sevenDays: return 7 * 24 * 3600
            }
        }
    }

    private var sanitized: (question: String, options: [String])? {
        PollMessage.sanitize(question: question, options: options)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("poll.question".localized) {
                    TextField("poll.question.placeholder".localized,
                              text: $question, axis: .vertical)
                        .lineLimit(1...4)
                        .focused($focused, equals: .question)
                        .onChange(of: question) { _, new in
                            if new.count > PollMessage.maxQuestionLength {
                                question = String(new.prefix(PollMessage.maxQuestionLength))
                            }
                        }
                    if question.count > PollMessage.maxQuestionLength - 40 {
                        Text("\(question.count)/\(PollMessage.maxQuestionLength)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("poll.options".localized) {
                    ForEach(options.indices, id: \.self) { i in
                        HStack(spacing: 10) {
                            Image(systemName: "circle")
                                .font(.footnote)
                                .foregroundStyle(.tertiary)
                                .accessibilityHidden(true)
                            TextField(optionPlaceholder(i), text: binding(for: i))
                                .focused($focused, equals: .option(i))
                                .submitLabel(i == options.count - 1 ? .done : .next)
                            if options.count > PollMessage.minOptions {
                                Button {
                                    removeOption(at: i)
                                } label: {
                                    Image(systemName: "minus.circle.fill")
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("poll.remove_option".localized)
                            }
                        }
                    }

                    if options.count < PollMessage.maxOptions {
                        Button {
                            options.append("")
                            focused = .option(options.count - 1)
                        } label: {
                            Label("poll.add_option".localized, systemImage: "plus.circle.fill")
                        }
                    }
                }

                Section {
                    Toggle("poll.multiple_answers".localized, isOn: $allowsMultipleAnswers)
                    Toggle("poll.anonymous".localized, isOn: $isAnonymous)
                    Picker("poll.duration".localized, selection: $duration) {
                        ForEach(PollDuration.allCases) { d in
                            Text(d.label).tag(d)
                        }
                    }
                } footer: {
                    // Says what anonymous DOES mean and what it cannot mean.
                    // A ballot is a message; it travels from its sender like
                    // every other message. Promising more than that on a
                    // peer-to-peer mesh would be a lie in 34 languages.
                    Text(isAnonymous
                         ? "poll.anonymous.hint".localized
                         : "poll.multiple_answers.hint".localized)
                }
            }
            .navigationTitle("poll.create".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("poll.send".localized) { send() }
                        .disabled(sanitized == nil)
                        .fontWeight(.semibold)
                }
            }
            .onAppear { focused = .question }
        }
    }

    private func optionPlaceholder(_ i: Int) -> String {
        String(format: "poll.option.placeholder".localized, i + 1)
    }

    private func binding(for i: Int) -> Binding<String> {
        Binding(
            get: { options.indices.contains(i) ? options[i] : "" },
            set: { new in
                guard options.indices.contains(i) else { return }
                options[i] = String(new.prefix(PollMessage.maxOptionLength))
            }
        )
    }

    private func removeOption(at i: Int) {
        guard options.count > PollMessage.minOptions, options.indices.contains(i) else { return }
        options.remove(at: i)
        focused = nil
    }

    private func send() {
        guard let clean = sanitized else { return }
        let poll = PollMessage(
            question: clean.question,
            options: clean.options,
            allowsMultipleAnswers: allowsMultipleAnswers,
            isAnonymous: isAnonymous,
            closesAt: duration.seconds.map { Date().addingTimeInterval($0) },
            creatorPublicKey: creatorPublicKey
        )
        onSend(poll)
        dismiss()
    }
}

// MARK: - Showing

struct PollMessageBubble: View {

    let poll: PollMessage
    // No `isFromCurrentUser`: a poll looks the same whoever asked. It was
    // declared and never read on both platforms — the chrome comes from the
    // caller's bubble surface, and `myPicks` is what the rows colour by.
    let myPublicKey: String
    /// Called with the full set of picks this device now wants on record.
    /// An EMPTY set is a retraction, not a no-op.
    let onVote: ([Int]) -> Void
    /// Resolves a public key to a name the owner of this device would
    /// recognise. The store only ever holds keys; names belong to the screen
    /// that has the contact list.
    let displayName: (String) -> String

    @ObservedObject private var store = PollStore.shared
    @State private var showVoters = false

    private var results: PollResults {
        store.results(for: poll, myPublicKey: myPublicKey)
    }

    private var hasVoted: Bool { !results.myPicks.isEmpty }

    var body: some View {
        let r = results
        VStack(alignment: .leading, spacing: 10) {
            header
            Text(poll.question)
                .font(.subheadline)
                .fontWeight(.semibold)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 8) {
                ForEach(poll.options.indices, id: \.self) { i in
                    PollOptionRow(
                        label: poll.options[i],
                        share: r.share(ofOption: i),
                        percent: r.percentLabel(forOption: i),
                        count: r.counts.indices.contains(i) ? r.counts[i] : 0,
                        isPicked: r.myPicks.contains(i),
                        isEnabled: !poll.isClosed,
                        allowsMultipleAnswers: poll.allowsMultipleAnswers,
                        onTap: { tap(option: i, current: r.myPicks) }
                    )
                }
            }

            footer(r)

            if showVoters && !poll.isAnonymous {
                voterList(r)
            }
        }
        .padding(14)
        .frame(maxWidth: 320, alignment: .leading)
    }

    // MARK: Pieces

    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: "chart.bar.xaxis")
                .font(.caption)
                .accessibilityHidden(true)
            Text("poll.title".localized)
                .font(.caption)
                .fontWeight(.semibold)
            Spacer(minLength: 4)
            if poll.isAnonymous {
                badge("poll.anonymous.badge".localized, icon: "eye.slash")
            }
            if poll.allowsMultipleAnswers {
                badge("poll.multiple_answers.badge".localized, icon: "checklist")
            }
        }
        .foregroundStyle(.secondary)
    }

    private func badge(_ text: String, icon: String) -> some View {
        HStack(spacing: 3) {
            Image(systemName: icon).font(.caption2).accessibilityHidden(true)
            Text(text).font(.caption2)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(Color.secondary.opacity(0.14), in: Capsule())
    }

    private func footer(_ r: PollResults) -> some View {
        HStack(spacing: 6) {
            Text(voteCountLabel(r.voterCount))
                .font(.caption2)
                .foregroundStyle(.secondary)

            if !poll.isAnonymous && r.voterCount > 0 {
                Button {
                    withAnimation(.easeInOut(duration: 0.18)) { showVoters.toggle() }
                } label: {
                    Text(showVoters ? "poll.hide_voters".localized : "poll.see_voters".localized)
                        .font(.caption2)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
            }

            Spacer(minLength: 4)

            Text(stateLabel)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func voterList(_ r: PollResults) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(poll.options.indices, id: \.self) { i in
                let keys = r.votersByOption.indices.contains(i) ? r.votersByOption[i] : []
                if !keys.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(poll.options[i])
                            .font(.caption2)
                            .fontWeight(.semibold)
                        Text(keys.map(displayName).joined(separator: ", "))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .padding(.top, 2)
    }

    private var stateLabel: String {
        if poll.isClosed { return "poll.closed".localized }
        if let closesAt = poll.closesAt {
            let f = RelativeDateTimeFormatter()
            f.unitsStyle = .short
            return String(format: "poll.closes_in".localized,
                          f.localizedString(for: closesAt, relativeTo: Date()))
        }
        return hasVoted ? "" : "poll.tap_to_vote".localized
    }

    private func voteCountLabel(_ n: Int) -> String {
        n == 1 ? "poll.votes.one".localized
               : String(format: "poll.votes".localized, n)
    }

    // MARK: Voting

    /// Single answer: tapping another option moves the vote, tapping the one
    /// already chosen does nothing — a poll is not a thing you un-answer by
    /// accident. Multiple answers: every tap toggles, and emptying the set is
    /// an explicit retraction.
    private func tap(option i: Int, current: Set<Int>) {
        guard !poll.isClosed, poll.isValidOptionIndex(i) else { return }
        if poll.allowsMultipleAnswers {
            var next = current
            if next.contains(i) { next.remove(i) } else { next.insert(i) }
            onVote(next.sorted())
        } else {
            guard !current.contains(i) else { return }
            onVote([i])
        }
    }
}

// MARK: - One answer

private struct PollOptionRow: View {
    let label: String
    let share: Double
    let percent: Int
    let count: Int
    let isPicked: Bool
    let isEnabled: Bool
    let allowsMultipleAnswers: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    Image(systemName: mark)
                        .font(.footnote)
                        .foregroundStyle(isPicked ? AnyShapeStyle(.tint) : AnyShapeStyle(.tertiary))
                        .accessibilityHidden(true)

                    // Both of these matter: without `fixedSize` a long answer
                    // is squeezed to one letter per line, and without the
                    // priority on the number the percentage is what gets
                    // squeezed instead.
                    Text(label)
                        .font(.footnote)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    Text("\(percent)%")
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                        .layoutPriority(1)
                }

                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.secondary.opacity(0.18))
                        Capsule()
                            .fill(isPicked ? AnyShapeStyle(.tint) : AnyShapeStyle(Color.secondary.opacity(0.55)))
                            .frame(width: max(0, min(1, share)) * geo.size.width)
                    }
                }
                .frame(height: 6)
                .animation(.easeOut(duration: 0.25), value: share)
            }
            // __SEGMENT_HIT_AREA_2026_10_26__ Same defect as the Messages/Groups
            // segments: the row has no background, so only the mark, the answer
            // glyphs and the percentage voted. The gap between them, and the
            // whole result bar under them, did nothing.
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(String(format: "poll.accessibility.option".localized, percent, count))
        .accessibilityAddTraits(isPicked ? [.isButton, .isSelected] : .isButton)
    }

    private var mark: String {
        if allowsMultipleAnswers {
            return isPicked ? "checkmark.square.fill" : "square"
        }
        return isPicked ? "largecircle.fill.circle" : "circle"
    }
}
