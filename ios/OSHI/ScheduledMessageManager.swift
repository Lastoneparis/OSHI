//
//  ScheduledMessageManager.swift
//  OSHI - AI Scheduled "Thinking of You" Messages
//  Generates heartfelt messages using Apple Intelligence and sends at scheduled time
//

import SwiftUI
import UserNotifications
import Combine
import BackgroundTasks

// MARK: - Scheduled Message Model

struct ScheduledMessage: Codable, Identifiable, Equatable {
    let id: UUID
    let conversationId: String       // Recipient public key
    let conversationName: String
    let scheduledTime: Date          // UTC delivery time
    let generatedContent: String     // AI or template message
    var userEditedContent: String?   // User edit (overrides AI)
    let relationshipTone: RelationshipTone
    let wasAIGenerated: Bool
    var status: ScheduleStatus
    let createdAt: Date

    enum ScheduleStatus: String, Codable {
        case pending, sent, cancelled, failed
    }

    enum RelationshipTone: String, Codable, CaseIterable {
        case romantic, friendly, family, casual, formal

        var icon: String {
            switch self {
            case .romantic: return "heart.fill"
            case .friendly: return "face.smiling"
            case .family: return "house.fill"
            case .casual: return "hand.wave.fill"
            case .formal: return "briefcase.fill"
            }
        }

        var localizedKey: String {
            "scheduled_msg.tone.\(rawValue)"
        }

        var color: Color {
            switch self {
            case .romantic: return .pink
            case .friendly: return .orange
            case .family: return .green
            case .casual: return .blue
            case .formal: return .gray
            }
        }
    }

    var effectiveContent: String {
        userEditedContent ?? generatedContent
    }
}

// MARK: - Time of Day

enum TimeOfDay: String {
    case morning, afternoon, evening, night

    static func current(for date: Date = Date()) -> TimeOfDay {
        let hour = Calendar.current.component(.hour, from: date)
        switch hour {
        case 6..<12: return .morning
        case 12..<17: return .afternoon
        case 17..<21: return .evening
        default: return .night
        }
    }
}

// MARK: - Scheduled Message Manager

class ScheduledMessageManager: ObservableObject {
    static let shared = ScheduledMessageManager()
    static let bgTaskIdentifier = "com.moriceau.oshi.scheduled-message-delivery"

    @Published var scheduledMessages: [ScheduledMessage] = []
    private var monitorTimer: AnyCancellable?
    private let storageFile = "scheduled_messages.json"

    private init() {
        loadMessages()
        startMonitor()
    }

    // MARK: - BGTaskScheduler Registration

    /// Call this from AppDelegate/App init to register the background task
    static func registerBackgroundTask() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: bgTaskIdentifier,
            using: .main
        ) { task in
            guard let bgTask = task as? BGAppRefreshTask else { return }
            ScheduledMessageManager.shared.handleBackgroundTask(bgTask)
        }
    }

    /// 🔧 FIX R22: Track IDs sent during this BGTask to prevent double-send on expiry retry
    private var sentDuringBGTask = Set<UUID>()

    private func handleBackgroundTask(_ task: BGAppRefreshTask) {
        // Schedule the next background refresh
        scheduleBackgroundRefresh()

        // 🔧 FIX R22: Clear tracking set at start, populate during sends
        sentDuringBGTask.removeAll()

        task.expirationHandler = { [weak self] in
            // Save any partial progress before iOS kills us
            self?.saveMessages()
            task.setTaskCompleted(success: false)
        }

        // Send any overdue messages
        sendOverdueMessages()
        task.setTaskCompleted(success: true)
    }

    /// Schedule background app refresh for the next pending message
    func scheduleBackgroundRefresh() {
        let nextPending = scheduledMessages
            .filter { $0.status == .pending }
            .sorted { $0.scheduledTime < $1.scheduledTime }
            .first

        guard let next = nextPending else { return }

        let request = BGAppRefreshTaskRequest(identifier: Self.bgTaskIdentifier)
        // Schedule 1 minute before the message is due
        request.earliestBeginDate = next.scheduledTime.addingTimeInterval(-60)

        do {
            try BGTaskScheduler.shared.submit(request)
            OshiLog.messaging.info("📋 Background refresh scheduled for \(next.scheduledTime)")
        } catch {
            OshiLog.messaging.info("❌ Failed to schedule background refresh: \(error)")
        }
    }

    // MARK: - Create

    func schedule(_ message: ScheduledMessage) {
        scheduledMessages.append(message)
        saveMessages()
        scheduleNotification(for: message)
        // Schedule background task for delivery even if app is killed
        scheduleBackgroundRefresh()
    }

    // MARK: - Cancel

    func cancel(_ messageId: UUID) {
        if let idx = scheduledMessages.firstIndex(where: { $0.id == messageId }) {
            scheduledMessages[idx].status = .cancelled
            UNUserNotificationCenter.current().removePendingNotificationRequests(
                withIdentifiers: ["scheduled_msg_\(messageId.uuidString)"]
            )
            saveMessages()
        }
    }

    // MARK: - Send Overdue

    func sendOverdueMessages() {
        let now = Date()
        for i in scheduledMessages.indices {
            if scheduledMessages[i].status == .pending && scheduledMessages[i].scheduledTime <= now {
                sendMessage(at: i)
            }
        }
    }

    // MARK: - Send

    private func sendMessage(at index: Int) {
        guard index < scheduledMessages.count else { return }
        let msg = scheduledMessages[index]

        // 🔧 FIX R22: Skip if already sent during this BGTask cycle (prevents double-send on expiry retry)
        guard !sentDuringBGTask.contains(msg.id) else {
            OshiLog.messaging.info("📋 ScheduledMessage: Skipping \(msg.id) — already sent this cycle")
            return
        }

        // Direct delivery via MessageManager (works even if ChatView not active)
        if let msgManager = MessageManager.shared,
           let identityManager = MessageManager.sharedIdentityManager,
           let meshManager = MessageManager.sharedMeshManager {
            let outcome = msgManager.sendMessage(
                content: msg.effectiveContent,
                recipientAddress: msg.conversationId,
                identityManager: identityManager,
                meshManager: meshManager
            )
            // `sendMessage` can now refuse (100 msg/min limit, or a body too
            // long to send even split up). Marking a refused message `.sent`
            // and dropping it is exactly the silent loss this status field
            // exists to prevent.
            switch outcome {
            case .rateLimited(let retryAfter):
                // Leave it `.pending`; the 30-second monitor picks it up again.
                OshiLog.messaging.info("📋 ScheduledMessage: held — rate limited, retrying after \(retryAfter)s")
                saveMessages()
                return
            case .tooLong:
                scheduledMessages[index].status = .failed
                sentDuringBGTask.insert(msg.id)
                OshiLog.messaging.info("📋 ScheduledMessage: FAILED — body too long to send even split up")
                saveMessages()
                return
            // __BLOCKED_NOTICE_2026_09_18__ Le contact a été bloqué ENTRE la
            // programmation et l'échéance. Échec définitif, pas une attente: le
            // moniteur des 30 secondes le repousserait sans fin, et la personne
            // qui a bloqué a dit le contraire de « réessaie plus tard ».
            case .blocked:
                scheduledMessages[index].status = .failed
                sentDuringBGTask.insert(msg.id)
                OshiLog.messaging.info("📋 ScheduledMessage: ÉCHEC — destinataire bloqué")
                saveMessages()
                return
            // __LORA_REPLY_REFUSAL_2026_10_26__ Même raison que le blocage:
            // un fil « lora! » n'est adressé à aucune clé, donc l'échéance ne
            // changera rien et le moniteur des 30 secondes le repousserait
            // sans fin. Échec définitif.
            case .loRaUnidentified:
                scheduledMessages[index].status = .failed
                sentDuringBGTask.insert(msg.id)
                OshiLog.messaging.info("📋 ScheduledMessage: ÉCHEC — fil LoRa sans identité vérifiée")
                saveMessages()
                return
            // __LORA_INTEROP_REPLY_2026_10_28__ The three limits of the interop
            // lane, split by whether WAITING can change the answer — which is the
            // same question the blocked and rate-limited cases above turn on.
            //
            // No radio is TRANSIENT: a node gets plugged back in or comes back
            // into Bluetooth range, and then the send works. So it is held
            // `.pending` like a rate limit, not failed. The other two never
            // change by waiting: a photo will still be a photo at the next tick,
            // and a body over one frame will still be over it.
            case .loRaInteropNoRadio:
                OshiLog.messaging.info("📋 ScheduledMessage: held — no LoRa radio attached")
                saveMessages()
                return
            case .loRaInteropMediaUnsupported:
                scheduledMessages[index].status = .failed
                sentDuringBGTask.insert(msg.id)
                OshiLog.messaging.info("📋 ScheduledMessage: ÉCHEC — LoRa ne porte que du texte court")
                saveMessages()
                return
            case .loRaInteropTooLong(let bytes, let limit):
                scheduledMessages[index].status = .failed
                sentDuringBGTask.insert(msg.id)
                OshiLog.messaging.info("📋 ScheduledMessage: ÉCHEC — \(bytes) B pour une trame de \(limit) B")
                saveMessages()
                return
            // __CHANNEL_ROOMS_ALL_SENT_ON_SLOT_ZERO_2026_11_07__ A radio IS
            // attached (the no-radio case above is checked first) and it has no
            // such channel. Waiting cannot fix that — only the user renaming or
            // re-adding the channel can — so this is definitive, like the two
            // cases above it, rather than a deadline the monitor pushes forever.
            case .loRaChannelUnknown:
                scheduledMessages[index].status = .failed
                sentDuringBGTask.insert(msg.id)
                OshiLog.messaging.info("📋 ScheduledMessage: ÉCHEC — canal LoRa absent de la radio connectée")
                saveMessages()
                return
            case .sent, .sentAsParts:
                break
            }
            scheduledMessages[index].status = .sent
            sentDuringBGTask.insert(msg.id) // 🔧 FIX R22: Track to prevent double-send
            OshiLog.messaging.info("📋 ScheduledMessage: Sent directly via MessageManager to \(msg.conversationName)")
        } else {
            // Fallback: notification (requires active ChatView)
            NotificationCenter.default.post(
                name: NSNotification.Name("SendScheduledMessage"),
                object: nil,
                userInfo: [
                    "recipient": msg.conversationId,
                    "content": msg.effectiveContent,
                    "messageId": msg.id.uuidString
                ]
            )
            scheduledMessages[index].status = .sent
            sentDuringBGTask.insert(msg.id) // 🔧 FIX R22: Track to prevent double-send
            OshiLog.messaging.info("📋 ScheduledMessage: Sent via notification fallback to \(msg.conversationName)")
        }
        saveMessages()
    }

    // MARK: - Monitor

    private func startMonitor() {
        monitorTimer = Timer.publish(every: 30, on: .main, in: .common)
            .autoconnect()
            .sink { [weak self] _ in
                self?.sendOverdueMessages()
            }
    }

    // MARK: - Notification

    private func scheduleNotification(for message: ScheduledMessage) {
        let content = UNMutableNotificationContent()
        content.title = "scheduled_msg.notification.title".localized
        content.body = String(format: "scheduled_msg.notification.body".localized, message.conversationName)
        content.sound = .default

        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: message.scheduledTime)
        let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)

        let request = UNNotificationRequest(
            identifier: "scheduled_msg_\(message.id.uuidString)",
            content: content,
            trigger: trigger
        )
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - Persistence

    private func saveMessages() {
        let url = getDocumentsURL().appendingPathComponent(storageFile)
        if let data = try? JSONEncoder().encode(scheduledMessages) {
            // __ATOMIC_SCHEDULED_WRITE_2026_09_12__ A plain whole-file write can be
            // interrupted (crash, jetsam, kill during a background send) and leave
            // `scheduled_messages.json` truncated. `loadMessages` below swallows the
            // decode failure with `try?` and simply leaves the list empty, so a torn
            // write silently erases every pending scheduled message with nothing to
            // show for it. Every other queue in the app (`FileStorage`,
            // `PushRetryQueue`, `RevealOutbox`) already writes atomically.
            try? AtRestFile.write(data, to: url)  // __PLAINTEXT_LEFTOVERS_2026_09_24__ chiffré au repos (ancien clair lu puis rechiffré)
        }
    }

    private func loadMessages() {
        let url = getDocumentsURL().appendingPathComponent(storageFile)
        if let data = try? AtRestFile.read(url),  // __PLAINTEXT_LEFTOVERS_2026_09_24__
           let messages = try? JSONDecoder().decode([ScheduledMessage].self, from: data) {
            scheduledMessages = messages
        }
    }

    private func getDocumentsURL() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
    }

    // MARK: - AI Generation

    func generateMessage(
        conversationContext: String,
        recipientName: String,
        tone: ScheduledMessage.RelationshipTone,
        scheduledTime: Date
    ) async -> (content: String, wasAI: Bool) {
        let timeOfDay = TimeOfDay.current(for: scheduledTime)
        let result = await NLPManager.shared.processGenerateThinkingOfYou(
            conversationContext: conversationContext,
            recipientName: recipientName,
            tone: tone,
            timeOfDay: timeOfDay
        )
        return result
    }

    func pendingMessages(for conversationId: String) -> [ScheduledMessage] {
        scheduledMessages.filter { $0.conversationId == conversationId && $0.status == .pending }
    }
}

// MARK: - Create Scheduled Message View

struct CreateScheduledMessageView: View {
    @Binding var isPresented: Bool
    let conversationId: String
    let conversationName: String
    let conversationContext: String

    @ObservedObject private var manager = ScheduledMessageManager.shared
    @State private var selectedTone: ScheduledMessage.RelationshipTone = .friendly
    @State private var scheduledDate = Date().addingTimeInterval(3600)
    @State private var generatedText = ""
    @State private var editedText = ""
    @State private var isGenerating = false
    @State private var wasAIGenerated = false

    private let brandColor = Theme.brand
    private let accentPink = Color(red: 0.94, green: 0.28, blue: 0.56)

    var body: some View {
        NavigationView {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 20) {
                        // AI Header
                        headerSection

                        // Tone picker
                        tonePickerSection

                        // Date/time picker (compact)
                        datePickerSection

                        // Generate button
                        generateButton

                        // Preview/edit (auto-scrolls here after generation)
                        if !generatedText.isEmpty {
                            previewSection
                                .id("previewSection")
                        }
                    }
                    .padding()
                }
                .onChange(of: generatedText) {
                    if !generatedText.isEmpty {
                        withAnimation(.easeInOut(duration: 0.3)) {
                            proxy.scrollTo("previewSection", anchor: .top)
                        }
                    }
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("scheduled_msg.title".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { isPresented = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("scheduled_msg.schedule".localized) {
                        scheduleMessage()
                    }
                    .disabled(generatedText.isEmpty || editedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .font(.headline)
                }
            }
        }
    }

    // MARK: - Sections

    private var headerSection: some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [brandColor, accentPink],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 56, height: 56)
                Image(systemName: "heart.text.clipboard")
                    .font(.scaled(24))
                    .foregroundColor(.white)
            }
            Text("scheduled_msg.subtitle".localized)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var tonePickerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("scheduled_msg.tone".localized)
                .font(.caption.weight(.medium))
                .foregroundColor(.secondary)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(ScheduledMessage.RelationshipTone.allCases, id: \.rawValue) { tone in
                        Button {
                            selectedTone = tone
                        } label: {
                            VStack(spacing: 5) {
                                Image(systemName: tone.icon)
                                    .font(.scaled(20))
                                Text(tone.localizedKey.localized)
                                    .font(.caption2.weight(.medium))
                            }
                            .foregroundColor(selectedTone == tone ? .white : tone.color)
                            .frame(width: 70, height: 60)
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(selectedTone == tone ? tone.color : tone.color.opacity(0.1))
                            )
                        }
                    }
                }
            }
        }
    }

    private var datePickerSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("scheduled_msg.when".localized)
                .font(.caption.weight(.medium))
                .foregroundColor(.secondary)

            DatePicker("scheduled_msg.when".localized, selection: $scheduledDate, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                .datePickerStyle(.compact)
                .labelsHidden()
                .tint(brandColor)
                .padding(12)
                .oledGlassCard(RoundedRectangle(cornerRadius: 16), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                .cornerRadius(16)
        }
    }

    private var generateButton: some View {
        Button {
            generateAIMessage()
        } label: {
            HStack(spacing: 8) {
                if isGenerating {
                    ProgressView()
                        .tint(.white)
                } else {
                    Image(systemName: "sparkles")
                }
                Text(generatedText.isEmpty ? "scheduled_msg.generating".localized : "scheduled_msg.regenerate".localized)
                    .font(.headline)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                LinearGradient(colors: [brandColor, accentPink], startPoint: .leading, endPoint: .trailing)
            )
            .foregroundColor(.white)
            .cornerRadius(14)
        }
        .disabled(isGenerating)
    }

    private var previewSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("scheduled_msg.preview".localized)
                    .font(.caption.weight(.medium))
                    .foregroundColor(.secondary)
                Spacer()
                if wasAIGenerated {
                    HStack(spacing: 3) {
                        Image(systemName: "cpu")
                            .font(.scaled(9))
                        Text("AI")
                            .font(.scaled(10, weight: .bold))
                    }
                    .foregroundColor(.green)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.green.opacity(0.1))
                    .cornerRadius(6)
                }
            }

            TextEditor(text: $editedText)
                .privateTypingComposer()  // __KEYBOARD_PRIVACY_2026_10_01__ no autocorrect learning when Private typing is on
                .font(.body)
                .scrollContentBackground(.hidden)
                .padding(12)
                .frame(minHeight: 80, maxHeight: 200)
                .oledGlassCard(RoundedRectangle(cornerRadius: 12), fallback: Color(.secondarySystemGroupedBackground))  // __OLED_GLASS_2026_11_16__
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(brandColor.opacity(0.2), lineWidth: 1)
                )
        }
    }

    // MARK: - Actions

    private func generateAIMessage() {
        isGenerating = true
        Task {
            let result = await manager.generateMessage(
                conversationContext: conversationContext,
                recipientName: conversationName,
                tone: selectedTone,
                scheduledTime: scheduledDate
            )
            await MainActor.run {
                generatedText = result.content
                editedText = result.content
                wasAIGenerated = result.wasAI
                isGenerating = false
            }
        }
    }

    private func scheduleMessage() {
        let msg = ScheduledMessage(
            id: UUID(),
            conversationId: conversationId,
            conversationName: conversationName,
            scheduledTime: scheduledDate,
            generatedContent: generatedText,
            userEditedContent: editedText != generatedText ? editedText : nil,
            relationshipTone: selectedTone,
            wasAIGenerated: wasAIGenerated,
            status: .pending,
            createdAt: Date()
        )
        manager.schedule(msg)
        isPresented = false
    }
}
