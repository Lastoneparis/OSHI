//
//  BotCommandsEditorView.swift
//  OSHI — the owner's screen for a bot's `/` commands.
//
//  __BOT_COMMANDS_2026_11_24__ Telegram makes you talk to @BotFather and type
//  `/setcommands` followed by a specially formatted message. Here it is a list:
//  add, edit, swipe to delete, drag to reorder, with two one-tap starters when
//  the list is empty. The rules live in BotCommands.swift; this is pixels.
//

import SwiftUI

struct BotCommandsEditorView: View {
    @Binding var bot: OSHIBot
    @StateObject private var model: BotCommandsEditorModel
    @State private var editing: EditTarget?

    private let tint = Theme.brand

    /// Which form is open: a new command, or an existing trigger.
    struct EditTarget: Identifiable {
        let trigger: BotTrigger?
        var id: String { trigger?.id.uuidString ?? "new" }
    }

    init(bot: Binding<OSHIBot>) {
        _bot = bot
        _model = StateObject(wrappedValue: BotCommandsEditorModel(bot: bot.wrappedValue) { saved in
            bot.wrappedValue = saved
            BotManager.shared.updateBot(saved)
        })
    }

    var body: some View {
        List {
            if model.commandTriggers.isEmpty {
                emptyState
            } else {
                Section {
                    ForEach(model.commandTriggers) { trigger in
                        Button { editing = EditTarget(trigger: trigger) } label: {
                            commandRow(trigger)
                        }
                        .buttonStyle(.plain)
                    }
                    .onDelete { model.remove(atOffsets: $0) }
                    .onMove { model.move(fromOffsets: $0, toOffset: $1) }

                    addButton
                } header: {
                    Text(String(format: "bot.commands.header".localized, bot.name))
                } footer: {
                    Text("bot.commands.footer".localized)
                }
            }

            Section {
                syncRow
            }
        }
        .navigationTitle("bot.commands.title".localized)
        .toolbar {
            if !model.commandTriggers.isEmpty {
                ToolbarItem(placement: .primaryAction) { EditButton() }
            }
        }
        .sheet(item: $editing) { target in
            NavigationStack {
                BotCommandFormView(model: model, trigger: target.trigger) { editing = nil }
            }
            .presentationDetents([.medium, .large])
        }
    }

    // MARK: Rows

    private func commandRow(_ trigger: BotTrigger) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("/" + BotCommandSpec.normalizeName(trigger.condition))
                    .font(.system(.body, design: .monospaced).weight(.semibold))
                    .foregroundColor(tint)
                let line = BotCommandList.describe(trigger, in: bot)
                Text(line.isEmpty ? "bot.commands.no_description".localized : line)
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }
            Spacer()
            if !trigger.isActive {
                Text("bot.commands.paused".localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundColor(Color(.tertiaryLabel))
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }

    private var addButton: some View {
        Button { editing = EditTarget(trigger: nil) } label: {
            Label("bot.commands.add".localized, systemImage: "plus.circle.fill")
                .foregroundColor(tint)
        }
        .accessibilityIdentifier("bot.commands.add")
    }

    private var emptyState: some View {
        Section {
            VStack(spacing: 12) {
                Image(systemName: "slash.circle")
                    .font(.system(size: 44, weight: .light))
                    .foregroundColor(tint)
                Text("bot.commands.empty.title".localized)
                    .font(.headline)
                Text(String(format: "bot.commands.empty.body".localized, bot.name))
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                HStack(spacing: 8) {
                    ForEach(model.starters, id: \.self) { name in
                        Button { model.addStarter(name) } label: {
                            Label("/" + name, systemImage: "plus")
                                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                        .tint(tint)
                    }
                }
                Button { editing = EditTarget(trigger: nil) } label: {
                    Text("bot.commands.add".localized).fontWeight(.semibold)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(tint)
                .accessibilityIdentifier("bot.commands.add")
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
        }
    }

    @ViewBuilder
    private var syncRow: some View {
        switch model.sync {
        case .localOnly:
            Label("bot.commands.sync.local".localized, systemImage: "iphone")
                .font(.footnote).foregroundColor(.secondary)
        case .idle:
            Label("bot.commands.sync.remote".localized, systemImage: "network")
                .font(.footnote).foregroundColor(.secondary)
        case .syncing:
            HStack(spacing: 8) {
                ProgressView()
                Text("bot.commands.sync.syncing".localized).font(.footnote).foregroundColor(.secondary)
            }
        case .synced:
            Label("bot.commands.sync.done".localized, systemImage: "checkmark.circle.fill")
                .font(.footnote).foregroundColor(.green)
        case .failed:
            VStack(alignment: .leading, spacing: 6) {
                Label("bot.commands.sync.failed".localized, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote).foregroundColor(.orange)
                Button("common.retry".localized) { model.pushToServer() }
                    .font(.footnote.weight(.semibold))
                    .tint(tint)
            }
        }
    }
}

// MARK: - Add / edit one command

struct BotCommandFormView: View {
    @ObservedObject var model: BotCommandsEditorModel
    let trigger: BotTrigger?
    let onClose: () -> Void

    @State private var name = ""
    @State private var description = ""
    @State private var reply = ""
    @State private var problem: BotCommandSpec.Problem?
    @FocusState private var nameFocused: Bool

    private let tint = Theme.brand
    private var isLocal: Bool { model.sync == .localOnly }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 2) {
                    Text("/")
                        .font(.system(.body, design: .monospaced).weight(.semibold))
                        .foregroundColor(tint)
                    TextField("bot.commands.name_placeholder".localized, text: $name)
                        .font(.system(.body, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($nameFocused)
                        .onChange(of: name) { _, new in
                            let clean = BotCommandSpec.sanitizeWhileTyping(new)
                            if clean != new { name = clean }
                            problem = nil
                        }
                        .accessibilityIdentifier("bot.commands.name")
                }
            } header: {
                Text("bot.commands.name".localized)
            } footer: {
                Text("bot.commands.name_rule".localized)
            }

            Section {
                TextField("bot.commands.description_placeholder".localized, text: $description, axis: .vertical)
                    .lineLimit(1...4)
                    .onChange(of: description) { _, new in
                        if new.count > BotCommandSpec.maxDescriptionLength {
                            description = String(new.prefix(BotCommandSpec.maxDescriptionLength))
                        }
                        problem = nil
                    }
            } header: {
                Text("bot.description_label".localized)
            } footer: {
                HStack {
                    Text("bot.commands.description_hint".localized)
                    Spacer()
                    Text("\(description.count)/\(BotCommandSpec.maxDescriptionLength)")
                        .monospacedDigit()
                }
            }

            if isLocal {
                Section {
                    TextField("bot.commands.reply_placeholder".localized, text: $reply, axis: .vertical)
                        .lineLimit(2...6)
                } header: {
                    Text("bot.commands.reply".localized)
                } footer: {
                    Text("bot.commands.reply_hint".localized)
                }
            }

            if let problem {
                Section {
                    Label(problem.messageKey.localized, systemImage: "exclamationmark.circle.fill")
                        .foregroundColor(.red)
                        .font(.footnote)
                }
            }

            if let trigger {
                Section {
                    Button(role: .destructive) {
                        model.remove(triggerID: trigger.id)
                        onClose()
                    } label: {
                        Label("common.delete".localized, systemImage: "trash")
                    }
                }
            }
        }
        .navigationTitle((trigger == nil ? "bot.commands.add" : "bot.commands.edit").localized)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("common.cancel".localized, action: onClose)
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("common.save".localized) {
                    if let p = model.save(editing: trigger?.id, name: name,
                                          description: description, reply: reply) {
                        problem = p
                    } else {
                        onClose()
                    }
                }
                .disabled(name.isEmpty)
                .keyboardShortcut(.defaultAction)
            }
        }
        .onAppear {
            if let trigger {
                name = BotCommandSpec.normalizeName(trigger.condition)
                description = trigger.commandDescription ?? ""
                reply = trigger.responseTemplate
            } else {
                nameFocused = true
            }
        }
    }
}

// MARK: - Opened from a chat ("Add commands" in the `/` menu)

/// The editor in its own sheet, for the bot's owner, straight from the chat.
struct BotCommandsEditorSheet: View {
    @State private var bot: OSHIBot
    @Environment(\.dismiss) private var dismiss

    init(bot: OSHIBot) { _bot = State(initialValue: bot) }

    var body: some View {
        NavigationStack {
            BotCommandsEditorView(bot: $bot)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("common.done".localized) { dismiss() }
                    }
                }
        }
    }
}
