//
//  ScheduledCallView.swift
//  OSHI - Scheduled Call Event UI
//
//  Views for creating, displaying, and responding to scheduled call events
//

import SwiftUI

// MARK: - Brand Color
private let appBrandColor = Theme.brand
import EventKit

// MARK: - Brand Color
import EventKitUI

// MARK: - Brand Color

// MARK: - Create Scheduled Call Sheet

struct CreateScheduledCallView: View {
    let conversationId: String
    let conversationName: String
    let onSchedule: (ScheduledCallEvent) -> Void

    // __SCHEDULE_CALL_REDESIGN_2026_09_29__ One screen, thumb-first: who, quick
    // "when" chips (in 1 h, tonight, tomorrow morning/evening, or a full calendar),
    // a big readable date/time, duration chips, an optional title, and one button
    // that says exactly what will be sent. Same event, same wire.

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var selectedDate = CreateScheduledCallView.roundedUp(Date().addingTimeInterval(3600))
    @State private var title = ""
    @State private var duration: Int = 30 // minutes
    @State private var showCalendar = false
    @State private var chosenQuick: Int? = 0
    @FocusState private var isTitleFocused: Bool

    private let durations = [15, 30, 45, 60, 90, 120]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 22) {
                    header
                    whenSection
                    durationSection
                    titleSection
                    Text(timezoneInfoText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 4)
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 24)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
            .safeAreaInset(edge: .bottom, spacing: 0) { scheduleButton }
            .navigationTitle("event.create.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { dismiss() }
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .sensoryFeedback(.selection, trigger: selectedDate)
        .sensoryFeedback(.selection, trigger: duration)
    }

    // MARK: Sections

    private var header: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(Theme.brandGradient)
                Text(initials)
                    .font(.system(size: 20, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
            }
            .frame(width: 54, height: 54)
            .overlay(alignment: .bottomTrailing) {
                Image(systemName: "phone.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 22, height: 22)
                    .background(Circle().fill(Color.green))
                    .overlay(Circle().stroke(Color(.systemGroupedBackground), lineWidth: 2))
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(String(format: "event.create.with".localized, conversationName))
                    .font(.system(.title3, design: .rounded).weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Text(summaryLine)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .contentTransition(.numericText())
            }
            Spacer(minLength: 0)
        }
    }

    private var whenSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionLabel("event.create.when".localized, symbol: "clock")

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(quickOptions.enumerated()), id: \.offset) { index, option in
                        chip(option.label, selected: chosenQuick == index) {
                            withAnimation(.snappy) {
                                chosenQuick = index
                                selectedDate = option.date
                                showCalendar = false
                            }
                        }
                    }
                    chip("event.create.custom".localized, symbol: "calendar",
                         selected: chosenQuick == nil) {
                        withAnimation(.snappy) { chosenQuick = nil; showCalendar = true }
                    }
                }
                .padding(.horizontal, 2)
            }

            // The chosen moment, big — tap to open the full calendar.
            Button {
                withAnimation(.snappy) { showCalendar.toggle() }
            } label: {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(dayText)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.secondary)
                        Text(selectedDate, style: .time)
                            .font(.system(size: 40, weight: .semibold, design: .rounded))
                            .monospacedDigit()
                            .foregroundStyle(.primary)
                            .contentTransition(.numericText())
                    }
                    Spacer()
                    Image(systemName: showCalendar ? "chevron.up.circle.fill" : "chevron.down.circle.fill")
                        .font(.title2)
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(appBrandColor)
                }
                .padding(16)
                .background(card)
            }
            .buttonStyle(.plain)

            if showCalendar {
                VStack(spacing: 0) {
                    DatePicker("", selection: $selectedDate, in: Date()...,
                               displayedComponents: [.date])
                        .datePickerStyle(.graphical)
                        .labelsHidden()
                        .tint(appBrandColor)
                    Divider()
                    DatePicker("event.create.datetime".localized, selection: $selectedDate,
                               in: Date()..., displayedComponents: [.hourAndMinute])
                        .padding(.vertical, 10)
                }
                .padding(.horizontal, 12)
                .background(card)
                .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
                .onChange(of: selectedDate) { _, _ in
                    if let i = chosenQuick, quickOptions.indices.contains(i),
                       quickOptions[i].date != selectedDate { chosenQuick = nil }
                }
            }
        }
    }

    private var durationSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionLabel("event.create.duration".localized, symbol: "hourglass")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(durations, id: \.self) { mins in
                        chip(formatDuration(mins), selected: duration == mins) {
                            withAnimation(.snappy) { duration = mins }
                        }
                    }
                }
                .padding(.horizontal, 2)
            }
        }
    }

    private var titleSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionLabel("event.create.title.optional".localized, symbol: "text.cursor")
            TextField("event.create.title.placeholder".localized, text: $title)
                .focused($isTitleFocused)
                .submitLabel(.done)
                .padding(14)
                .background(card)
        }
    }

    private var scheduleButton: some View {
        Button(action: scheduleCall) {
            HStack(spacing: 10) {
                Image(systemName: "calendar.badge.checkmark")
                    .font(.system(size: 18, weight: .semibold))
                VStack(alignment: .leading, spacing: 0) {
                    Text("event.create.schedule".localized)
                        .font(.headline)
                    Text(summaryLine)
                        .font(.caption)
                        .opacity(0.85)
                }
                Spacer()
                Image(systemName: "arrow.up.circle.fill")
                    .font(.title2)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .background(Capsule().fill(Theme.brandGradient))
            .shadow(color: appBrandColor.opacity(0.35), radius: 12, y: 6)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .accessibilityHint(Text("accessibility.event.schedule.hint".localized))
    }

    // MARK: Pieces

    private var card: some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .fill(Color(.secondarySystemGroupedBackground))
    }

    private func sectionLabel(_ text: String, symbol: String) -> some View {
        Label(text, systemImage: symbol)
            .font(.footnote.weight(.semibold))
            .textCase(.uppercase)
            .foregroundStyle(.secondary)
    }

    private func chip(_ text: String, symbol: String? = nil, selected: Bool,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let symbol { Image(systemName: symbol).font(.caption.weight(.semibold)) }
                Text(text).font(.subheadline.weight(selected ? .semibold : .regular))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .foregroundColor(selected ? .white : .primary)
            .background(
                Capsule().fill(selected ? AnyShapeStyle(Theme.brandGradient)
                                        : AnyShapeStyle(Color(.secondarySystemGroupedBackground)))
            )
            .overlay(Capsule().strokeBorder(Color.primary.opacity(selected ? 0 : 0.08)))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: Values

    private struct QuickOption { let label: String; let date: Date }

    /// In 1 h, tonight 20:00 (if still ahead), tomorrow 09:00, tomorrow 18:00 —
    /// labels come from the system's relative formatting, so every language is right.
    private var quickOptions: [QuickOption] {
        let cal = Calendar.current
        let now = Date()
        var out: [QuickOption] = []
        let inAnHour = Self.roundedUp(now.addingTimeInterval(3600))
        let rel = RelativeDateTimeFormatter()
        rel.unitsStyle = .short
        out.append(QuickOption(label: rel.localizedString(fromTimeInterval: 3600), date: inAnHour))
        func at(_ dayOffset: Int, _ hour: Int) -> Date? {
            guard let day = cal.date(byAdding: .day, value: dayOffset, to: now) else { return nil }
            return cal.date(bySettingHour: hour, minute: 0, second: 0, of: day)
        }
        if let tonight = at(0, 20), tonight > now.addingTimeInterval(1800) {
            out.append(QuickOption(label: Self.relativeDay.string(from: tonight), date: tonight))
        }
        if let morning = at(1, 9) {
            out.append(QuickOption(label: Self.relativeDay.string(from: morning), date: morning))
        }
        if let evening = at(1, 18) {
            out.append(QuickOption(label: Self.relativeDay.string(from: evening), date: evening))
        }
        return out
    }

    private static let relativeDay: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        f.doesRelativeDateFormatting = true
        return f
    }()

    private var dayText: String {
        let f = DateFormatter()
        f.doesRelativeDateFormatting = true
        f.dateStyle = .full
        f.timeStyle = .none
        return f.string(from: selectedDate)
    }

    private var summaryLine: String {
        "\(Self.relativeDay.string(from: selectedDate)) · \(formatDuration(duration))"
    }

    private var initials: String {
        let parts = conversationName.split(separator: " ").prefix(2)
        let s = parts.compactMap { $0.first }.map(String.init).joined()
        return s.isEmpty ? "?" : s.uppercased()
    }

    /// Next quarter hour — nobody schedules a call at 14:37.
    static func roundedUp(_ date: Date) -> Date {
        let cal = Calendar.current
        let minute = cal.component(.minute, from: date)
        let add = (15 - minute % 15) % 15
        let base = cal.date(from: cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)) ?? date
        return cal.date(byAdding: .minute, value: add, to: base) ?? date
    }

    private var timezoneInfoText: String {
        let timezone = TimeZone.current
        let abbreviation = timezone.abbreviation() ?? ""
        let offset = timezone.secondsFromGMT() / 3600
        let sign = offset >= 0 ? "+" : ""
        return String(format: "event.timezone.info".localized, abbreviation, "\(sign)\(offset)")
    }

    private func formatDuration(_ minutes: Int) -> String {
        let f = DateComponentsFormatter()
        f.unitsStyle = .abbreviated
        f.allowedUnits = minutes >= 60 ? [.hour, .minute] : [.minute]
        return f.string(from: TimeInterval(minutes * 60)) ?? "\(minutes) min"
    }

    private func scheduleCall() {
        let event = ScheduledCallManager.shared.createEvent(
            for: conversationId,
            conversationName: conversationName,
            scheduledTime: selectedDate,
            title: title.isEmpty ? nil : title,
            duration: TimeInterval(duration * 60)  // Convert minutes to seconds
        )
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onSchedule(event)
        dismiss()
    }
}

// MARK: - Scheduled Call Event Row (for chat display)

struct ScheduledCallEventRow: View {
    let event: ScheduledCallEvent
    var isPreview: Bool = false
    var onAccept: (() -> Void)?
    var onDecline: (() -> Void)?
    var onJoinCall: (() -> Void)?
    
    @State private var showLocalTime = true
    @State private var showingCalendarOptions = false
    @State private var showingShareSheet = false
    @State private var icsFileURL: URL?
    @State private var calendarAddedSuccess = false
    @StateObject private var calendarHelper = CalendarExportHelper.shared
    
    private var myPublicKey: String {
        UserDefaults.standard.string(forKey: "publicKey") ?? ""
    }
    
    private var myResponse: ScheduledCallEvent.EventResponse? {
        event.responses[myPublicKey]
    }
    
    private var isCreator: Bool {
        event.createdBy == myPublicKey
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header
            HStack {
                Image(systemName: "calendar.badge.clock")
                    .font(.title2)
                    .foregroundColor(.orange)
                
                VStack(alignment: .leading, spacing: 2) {
                    Text(event.title ?? "event.call.scheduled".localized)
                        .font(.headline)

                    Text(String(format: "event.created.by".localized, event.createdByName))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .accessibilityElement(children: .combine)

                Spacer()

                // Status badge
                statusBadge
            }
            
            Divider()
            
            // Time display with timezone toggle
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(formattedDate)
                        .font(.title3)
                        .fontWeight(.semibold)
                    
                    Text(formattedTime)
                        .font(.title)
                        .fontWeight(.bold)
                        .foregroundColor(appBrandColor)
                    
                    // Timezone indicator
                    Button(action: {
                        withAnimation(.easeInOut(duration: 0.2)) { showLocalTime.toggle() }
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "globe")
                            Text(timezoneText)
                        }
                        .font(.caption)
                        .foregroundColor(.secondary)
                    }
                    .accessibilityLabel(Text("accessibility.event.timezone".localized))
                    .accessibilityValue(Text(timezoneText))
                    .accessibilityHint(Text("accessibility.event.timezone.hint".localized))
                }
                
                Spacer()
                
                // Duration if set
                if let duration = event.duration {
                    VStack {
                        Image(systemName: "timer")
                            .font(.title2)
                            .foregroundColor(.secondary)
                        Text(formatDuration(duration))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("event.create.duration".localized))
                    .accessibilityValue(Text(formatDuration(duration)))
                }
                
                // Countdown or status
                if !event.isPast && event.status != .cancelled {
                    countdownView
                }
            }
            
            // Response buttons (if pending and not creator)
            if !isPreview && !isCreator && myResponse == nil && event.status == .pending {
                Divider()
                
                HStack(spacing: 16) {
                    Button(action: { onDecline?() }) {
                        Label("event.decline".localized, systemImage: "xmark.circle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                    
                    Button(action: { onAccept?() }) {
                        Label("event.accept".localized, systemImage: "checkmark.circle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                }
            }
            
            // Show response status
            if let response = myResponse, !isCreator {
                HStack {
                    Image(systemName: response == .accepted ? "checkmark.circle.fill" : "xmark.circle.fill")
                        .foregroundColor(response == .accepted ? .green : .red)
                    Text(response == .accepted ? "event.you.accepted".localized : "event.you.declined".localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            
            // Join call button (if event is now and accepted)
            if !isPreview && event.isNow && (myResponse == .accepted || isCreator) && event.status == .confirmed {
                Divider()
                
                Button(action: { onJoinCall?() }) {
                    Label("event.join.call".localized, systemImage: "phone.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
            }
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.1), radius: 4, y: 2)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(event.isNow ? Color.green : Color.orange.opacity(0.3), lineWidth: event.isNow ? 2 : 1)
        )
    }
    
    @ViewBuilder
    private var statusBadge: some View {
        switch event.status {
        case .pending:
            Text("event.status.pending".localized)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.orange.opacity(0.2))
                .foregroundColor(.orange)
                .cornerRadius(8)
        case .confirmed:
            Text("event.status.confirmed".localized)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.green.opacity(0.2))
                .foregroundColor(.green)
                .cornerRadius(8)
        case .declined:
            Text("event.status.declined".localized)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.red.opacity(0.2))
                .foregroundColor(.red)
                .cornerRadius(8)
        case .cancelled:
            Text("event.status.cancelled".localized)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.gray.opacity(0.2))
                .foregroundColor(.gray)
                .cornerRadius(8)
        case .completed:
            Text("event.status.completed".localized)
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(appBrandColor.opacity(0.2))
                .foregroundColor(appBrandColor)
                .cornerRadius(8)
        }
    }
    
    private var formattedDate: String {
        let formatter = DateFormatter()
        formatter.timeZone = showLocalTime ? .current : TimeZone(identifier: event.creatorTimezone)
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: event.scheduledTime)
    }
    
    private var formattedTime: String {
        let formatter = DateFormatter()
        formatter.timeZone = showLocalTime ? .current : TimeZone(identifier: event.creatorTimezone)
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: event.scheduledTime)
    }
    
    private var timezoneText: String {
        let tz = showLocalTime ? TimeZone.current : TimeZone(identifier: event.creatorTimezone) ?? .current
        return tz.abbreviation() ?? tz.identifier
    }
    
    private var countdownView: some View {
        VStack(alignment: .trailing) {
            let remaining = event.scheduledTime.timeIntervalSinceNow
            
            if remaining > 0 {
                if remaining < 300 { // Less than 5 minutes
                    Text("event.starting.soon".localized)
                        .font(.caption)
                        .foregroundColor(.green)
                        .fontWeight(.semibold)
                } else if remaining < 3600 { // Less than 1 hour
                    let minutes = Int(remaining / 60)
                    Text(String(format: "event.in.minutes".localized, minutes))
                        .font(.caption)
                        .foregroundColor(.orange)
                } else if remaining < 86400 { // Less than 24 hours
                    let hours = Int(remaining / 3600)
                    Text(String(format: "event.in.hours".localized, hours))
                        .font(.caption)
                        .foregroundColor(.secondary)
                } else {
                    let days = Int(remaining / 86400)
                    Text(String(format: "event.in.days".localized, days))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
    
    private func formatDuration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        if minutes < 60 {
            return "\(minutes) min"
        } else {
            let hours = minutes / 60
            let remainingMins = minutes % 60
            if remainingMins == 0 {
                return "\(hours)h"
            }
            return "\(hours)h \(remainingMins)m"
        }
    }
}

// MARK: - Upcoming Call Alert

struct UpcomingCallAlertView: View {
    let event: ScheduledCallEvent
    let onJoinCall: () -> Void
    let onDismiss: () -> Void
    
    @State private var ringPulse = false
    
    var body: some View {
        VStack(spacing: 20) {
            // Animated bell
            ZStack {
                Circle()
                    .fill(Color.orange.opacity(0.2))
                    .frame(width: 100, height: 100)
                    .scaleEffect(ringPulse ? 1.3 : 1.0)
                    .opacity(ringPulse ? 0 : 0.5)
                
                Circle()
                    .fill(Color.orange.opacity(0.3))
                    .frame(width: 80, height: 80)
                
                Image(systemName: "bell.fill")
                    .font(.scaled(40))
                    .foregroundColor(.orange)
                    .rotationEffect(.degrees(ringPulse ? 15 : -15))
            }
            .onAppear {
                withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) {
                    ringPulse = true
                }
            }
            .accessibilityHidden(true)

            VStack(spacing: 8) {
                Text("event.reminder.title".localized)
                    .font(.title2)
                    .fontWeight(.bold)
                
                Text(event.conversationName)
                    .font(.headline)
                    .foregroundColor(.secondary)
                
                Text(event.formattedTime())
                    .font(.title)
                    .fontWeight(.semibold)
                    .foregroundColor(appBrandColor)
            }
            
            HStack(spacing: 20) {
                Button(action: onDismiss) {
                    Text("event.remind.later".localized)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                
                Button(action: onJoinCall) {
                    Label("event.join.call".localized, systemImage: "phone.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
            }
        }
        .padding(24)
        .frame(maxWidth: 460)
        .background(
            RoundedRectangle(cornerRadius: 24)
                .fill(Color(.systemBackground))
                .shadow(color: .black.opacity(0.2), radius: 20)
        )
        .padding(40)
    }
}

// MARK: - Preview

#Preview("Create Event") {
    CreateScheduledCallView(
        conversationId: "test123",
        conversationName: "John Doe"
    ) { event in
        OshiLog.call.info("Scheduled: \(event)")
    }
}

#Preview("Event Row") {
    VStack(spacing: 20) {
        ScheduledCallEventRow(
            event: ScheduledCallEvent(
                conversationId: "test",
                conversationName: "Alice",
                createdBy: "other",
                createdByName: "Alice",
                scheduledTime: Date().addingTimeInterval(3600),
                title: "Weekly Sync",
                duration: 1800
            ),
            onAccept: {},
            onDecline: {},
            onJoinCall: {}
        )
    }
    .padding()
}
