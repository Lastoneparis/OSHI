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

    static func commands(from bots: [OSHIBot]) -> [SlashCommand] {
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
                out.append(SlashCommand(command: "/\(name)",
                                        description: bot.description,
                                        botName: bot.name,
                                        botEmoji: bot.avatarEmoji,
                                        botID: bot.id))
            }
        }
        return out.sorted { $0.command.lowercased() < $1.command.lowercased() }
    }

    /// Ce que le menu montre pour le texte courant du compositeur.
    ///
    /// Renvoyer `[]` veut dire « pas de barre » — pas « barre vide ». La vue
    /// s'appuie la-dessus: une barre vide qui flotte au-dessus du clavier est
    /// pire que pas de barre du tout.
    static func matches(for text: String, bots: [OSHIBot]) -> [SlashCommand] {
        guard let q = query(in: text) else { return [] }
        let all = commands(from: bots)
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
/// Elle se comporte comme `SmartRepliesView`, sa voisine immediate: une bande
/// qui apparait, qui se touche, et qui disparait. Elle ne s'affiche jamais vide
/// — l'appelant ne la construit que si `commands` n'est pas vide.
struct SlashCommandBar: View {
    let commands: [SlashCommand]
    let tint: Color
    let onPick: (SlashCommand) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(commands) { cmd in
                Button { onPick(cmd) } label: {
                    HStack(spacing: 10) {
                        Text(cmd.botEmoji.isEmpty ? "🤖" : cmd.botEmoji)
                            .font(.title3)
                            .frame(width: 28)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(cmd.command)
                                .font(.system(.subheadline, design: .monospaced).weight(.semibold))
                                .foregroundColor(tint)
                            if !cmd.description.isEmpty {
                                Text(cmd.description)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }
                        }

                        Spacer(minLength: 8)

                        Text(cmd.botName)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("\(cmd.command) — \(cmd.botName)"))

                if cmd.id != commands.last?.id {
                    Divider().padding(.leading, 52)
                }
            }
        }
        // Plafonnee: dix bots bavards ne doivent pas manger l'ecran. Au-dela,
        // ca defile.
        .frame(maxHeight: 220)
        .background(Color(.systemBackground))
        .overlay(Divider(), alignment: .top)
    }
}
