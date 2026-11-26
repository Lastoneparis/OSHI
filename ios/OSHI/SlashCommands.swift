//
//  SlashCommands.swift
//  OSHI — le menu `/` du compositeur, comme sur Telegram.
//
//  __SLASH_MENU_2026_08_26__ Les bots portaient DEJA des declencheurs de type
//  `.command` (`/help`, `/echo`, `/stats`) : le codec existait, la porte
//  d'entree non. Il fallait connaitre la commande par coeur et la taper
//  exactement, ce qui revient a dire qu'elle n'existait pas pour l'utilisateur.
//  Cf. [[unlocking-a-feature-is-not-arming-it]] : cabler une fonctionnalite
//  n'est pas l'ARMER.
//
//  Tout ce qui est ici est du texte pur -> une liste. Pas de reseau, pas
//  d'etat, pas de `BotManager` : la vue lui passe les bots. C'est ce qui rend
//  la regle testable sans simulateur et sans faux.
//

import Foundation
import SwiftUI

/// Une commande proposee dans le menu, avec de quoi l'afficher.
struct SlashCommand: Identifiable, Equatable, Hashable {
    /// `/help` — toujours avec la barre oblique, telle qu'elle part au bot.
    let command: String
    /// Ce que la commande fait. Vide est permis: la ligne s'affiche quand meme.
    let description: String
    let botName: String
    let botEmoji: String
    let botID: UUID
    /// __BOT_COMMANDS_2026_11_24__ Tapping a BOT command sends it at once, as on
    /// Telegram (a bot command usually needs nothing more). A built-in such as
    /// `/pay` takes an argument, so tapping it fills the composer instead.
    var sendsOnPick: Bool = false

    var id: String { "\(botID.uuidString)\(command)" }
}

enum SlashCommands {

    // MARK: - Quand le menu s'ouvre

    /// Le fragment tape apres `/`, ou `nil` quand le menu ne doit PAS s'ouvrir.
    ///
    /// Telegram ouvre le menu des que le message commence par `/` et le referme
    /// des qu'une espace est saisie — parce qu'apres l'espace on ecrit les
    /// ARGUMENTS, plus le nom de la commande. On copie exactement ca.
    ///
    /// Trois refus qui comptent:
    ///   • `"bonjour /help"`  — le `/` n'est pas en tete, c'est du texte;
    ///   • `"/help oui"`      — l'espace a ete tapee, on argumente;
    ///   • `"/"` suivi d'un retour a la ligne.
    /// Et `"/"` tout seul OUVRE le menu, sur la liste complete: c'est le geste
    /// de decouverte, celui qui repond a « quelles commandes existent ? ».
    static func query(in text: String) -> String? {
        guard text.hasPrefix("/") else { return nil }
        let body = text.dropFirst()
        guard !body.contains(" "), !body.contains("\n") else { return nil }
        return String(body).lowercased()
    }

    // MARK: - Ce qu'il y a a proposer

    /// Les commandes de ces bots, dedupliquees et triees.
    ///
    /// Un declencheur `.command` peut avoir ete saisi avec ou sans la barre
    /// (`BotManager` l'admet aux deux formes a l'execution) : on normalise ici,
    /// sinon la meme commande apparaitrait deux fois dans le menu.
    /// Les commandes qu'OSHI repond lui-meme, par opposition aux declencheurs
    /// d'un bot.
    ///
    /// __CASHU_2026_10_25__ Ecrit pour une seule raison: `/pay` existait et
    /// personne ne pouvait le trouver. Une commande dont il faut vous avoir
    /// parle est une commande que presque personne n'utilise, et pour une
    /// fonctionnalite de paiement c'est la difference entre livre et utilisable.
    ///
    /// Volontairement court. Un menu qui liste tout ce que l'app sait faire est
    /// un second ecran de reglages; celui-ci existe pour qu'une fonctionnalite
    /// sans autre porte d'entree soit trouvable.
    static let builtInBotID = UUID(uuidString: "CA54D000-0000-4000-8000-000000000001")!

    static func builtIns() -> [SlashCommand] {
        [
            SlashCommand(command: CashuPayCommand.prefix,
                         description: NSLocalizedString("cashu_pay_command_description",
                                                        comment: "What /pay does, in the slash menu"),
                         botName: "OSHI",
                         botEmoji: "\u{1FA99}",
                         botID: builtInBotID),
        ]
    }

    /// __BOTS_PHASE1_2026_11_24__ A command whose description names an argument
    /// (`/remind <time> <text>`) inserts `/name ` on tap instead of sending a
    /// command that cannot work without one. Contract §4.4: `<arg>` = any `<…>`.
    static func takesArguments(_ description: String) -> Bool {
        guard let open = description.firstIndex(of: "<") else { return false }
        guard let close = description[open...].firstIndex(of: ">") else { return false }
        return description.distance(from: open, to: close) > 1
    }

    static func commands(from bots: [OSHIBot], extra: [SlashCommand] = []) -> [SlashCommand] {
        var seen = Set<String>()
        var out: [SlashCommand] = []
        for builtIn in builtIns() where seen.insert(builtIn.command.dropFirst().lowercased()).inserted {
            out.append(builtIn)
        }
        for bot in bots where bot.isEnabled {
            for trigger in bot.triggers
            where trigger.type == .command && trigger.isActive {
                let raw = trigger.condition.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !raw.isEmpty else { continue }
                let name = raw.hasPrefix("/") ? String(raw.dropFirst()) : raw
                guard !name.isEmpty, !name.contains(" ") else { continue }
                let key = name.lowercased()
                guard seen.insert(key).inserted else { continue }
                // __BOT_COMMANDS_2026_11_24__ the command's own line first; the
                // bot's description only when the owner wrote none.
                out.append(SlashCommand(command: "/\(name)",
                                        description: BotCommandList.describe(trigger, in: bot),
                                        botName: bot.name,
                                        botEmoji: bot.avatarEmoji,
                                        botID: bot.id,
                                        sendsOnPick: !takesArguments(BotCommandList.describe(trigger, in: bot))))
            }
        }
        // __BOTS_PHASE1_2026_11_24__ remote bots of the group (GroupBotDirectory).
        for cmd in extra where seen.insert(cmd.command.dropFirst().lowercased()).inserted {
            out.append(cmd)
        }
        return out.sorted { $0.command.lowercased() < $1.command.lowercased() }
    }

    /// Ce que le menu montre pour le texte courant du compositeur.
    ///
    /// Renvoyer `[]` veut dire « pas de barre » — pas « barre vide ». La vue
    /// s'appuie la-dessus: une barre vide qui flotte au-dessus du clavier est
    /// pire que pas de barre du tout.
    static func matches(for text: String, bots: [OSHIBot], extra: [SlashCommand] = []) -> [SlashCommand] {
        guard let q = query(in: text) else { return [] }
        let all = commands(from: bots, extra: extra)
        guard !q.isEmpty else { return all }
        return all.filter { $0.command.dropFirst().lowercased().hasPrefix(q) }
    }

    /// Le texte du compositeur apres qu'on a choisi une commande.
    ///
    /// L'espace finale est deliberee: la plupart des commandes prennent un
    /// argument, et sans elle l'utilisateur doit la taper avant de continuer.
    /// Elle referme aussi le menu par la regle de `query(in:)` — choisir une
    /// commande ne doit pas laisser la liste ouverte par-dessus le clavier.
    static func completion(for command: SlashCommand) -> String {
        command.command + " "
    }
}


// MARK: - La barre au-dessus du compositeur

/// La liste qui s'ouvre quand on tape `/`, posee au-dessus du champ de saisie.
///
/// __BOT_COMMANDS_2026_11_24__ Redone for bot chats: one line per command with
/// ITS OWN description (two lines max), a highlight driven by the hardware
/// keyboard (↑/↓, Return, Tab), an "insert" arrow on commands that send on tap
/// (to add arguments), and — only in a chat that has bots — a friendly line
/// instead of a vanishing bar when there is nothing to list.
struct SlashCommandBar: View {
    let commands: [SlashCommand]
    let tint: Color
    var highlightedIndex: Int? = nil
    var hint: SlashMenuContent.Hint? = nil
    /// Shown on the "no commands yet" line when the viewer owns the bot.
    var onAddCommands: ((UUID) -> Void)? = nil
    /// Fill the composer with `/name ` without sending.
    var onInsert: ((SlashCommand) -> Void)? = nil
    let onPick: (SlashCommand) -> Void

    var body: some View {
        // Short lists take their own height; long ones scroll inside 260 pt
        // (ten chatty bots must not eat the screen). Decided by row count, not
        // `ViewThatFits`: a flexible child under `.frame(maxHeight:)` takes the
        // full 260 pt and centres three rows in it.
        SwiftUI.Group {
            if commands.count + (hint == nil ? 0 : 1) <= 4 {
                list
            } else {
                ScrollViewReader { proxy in
                    ScrollView { list }
                        .frame(height: 260)
                        .onChange(of: highlightedIndex) { _, idx in
                            guard let idx, commands.indices.contains(idx) else { return }
                            withAnimation(.easeOut(duration: 0.12)) { proxy.scrollTo(commands[idx].id) }
                        }
                }
            }
        }
        .background(Color(.systemBackground))
        .overlay(Divider(), alignment: .top)
        .accessibilityIdentifier("slash.menu")
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(commands.enumerated()), id: \.element.id) { index, cmd in
                row(cmd, highlighted: index == highlightedIndex)
                    .id(cmd.id)
                if index < commands.count - 1 {
                    Divider().padding(.leading, 56)
                }
            }
            if let hint {
                if !commands.isEmpty { Divider().padding(.leading, 56) }
                hintRow(hint)
            }
        }
    }

    private func row(_ cmd: SlashCommand, highlighted: Bool) -> some View {
        HStack(spacing: 10) {
            Button { onPick(cmd) } label: {
                HStack(spacing: 10) {
                    Text(cmd.botEmoji.isEmpty ? "🤖" : cmd.botEmoji)
                        .font(.title3)
                        .frame(width: 34, height: 34)
                        .background(tint.opacity(0.10), in: Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        Text(cmd.command)
                            .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                            .foregroundColor(tint)
                        if !cmd.description.isEmpty {
                            Text(cmd.description)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    Spacer(minLength: 8)
                    Text(cmd.botName)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("\(cmd.command), \(cmd.description)"))
            .accessibilityHint(Text((cmd.sendsOnPick ? "slash.menu.a11y.sends" : "slash.menu.a11y.inserts").localized))

            if cmd.sendsOnPick, let onInsert {
                Button { onInsert(cmd) } label: {
                    Image(systemName: "arrow.up.left")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(.secondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("slash.menu.insert".localized))
                .help("slash.menu.insert".localized)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(highlighted ? tint.opacity(0.12) : Color.clear)
    }

    @ViewBuilder
    private func hintRow(_ hint: SlashMenuContent.Hint) -> some View {
        HStack(spacing: 10) {
            Image(systemName: hintIcon(hint))
                .font(.body)
                .foregroundColor(tint)
                .frame(width: 34, height: 34)
                .background(tint.opacity(0.10), in: Circle())
            Text(hintText(hint))
                .font(.footnote)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            if case let .botsHaveNoCommands(_, botID) = hint, let onAddCommands {
                Button { onAddCommands(botID) } label: {
                    Text("slash.menu.add_commands".localized)
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.borderless)
                .tint(tint)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }

    private func hintIcon(_ hint: SlashMenuContent.Hint) -> String {
        switch hint {
        case .botsHaveNoCommands: return "text.badge.plus"
        case .noMatch: return "magnifyingglass"
        }
    }

    private func hintText(_ hint: SlashMenuContent.Hint) -> String {
        switch hint {
        case .botsHaveNoCommands(let name, _):
            return String(format: "slash.menu.no_commands".localized, name)
        case .noMatch(let q):
            return String(format: "slash.menu.no_match".localized, "/" + q)
        }
    }
}

// MARK: - The commands button in a bot chat

/// The visible door to the menu (Telegram's "Menu" button): a `/` in the
/// composer pill of a chat that has bots. Tapping it types the `/` — so the
/// menu that opens is the very same one typing would open, with nothing new to
/// learn — and tapping again while the menu is open closes it.
struct SlashMenuButton: View {
    let isOpen: Bool
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isOpen ? "xmark" : "slash.circle")
                .font(.scaled(19, weight: .medium))
                .foregroundColor(tint)
                .frame(width: 34, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(Text((isOpen ? "slash.menu.close" : "slash.menu.open").localized))
        .help((isOpen ? "slash.menu.close" : "slash.menu.open").localized)
        .accessibilityIdentifier("composer.slash_menu")
    }
}
