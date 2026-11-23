//
//  RevealOutbox.swift
//  OSHI
//
//  __REVEAL_OUTBOX_2026_08_22__ Les couches d'une révélation progressive qui
//  restent à envoyer.
//
//  POURQUOI UNE FILE PERSISTÉE, ET PAS UN MINUTEUR
//  Une révélation dure de cinq minutes à UNE SEMAINE. Un `Timer` meurt avec le
//  processus; l'app sera fermée, l'appareil redémarré, la batterie vidée. Ce qui
//  survit, c'est une échéance ABSOLUE écrite sur le disque et rejouée au
//  démarrage et à chaque retour au premier plan.
//
//  La conséquence est assumée: les couches partent quand l'expéditeur ouvre son
//  app, pas à la seconde près. Le destinataire, lui, avance à l'heure — sa
//  planification est locale et son compte à rebours est juste. Une couche en
//  retard rend l'image moins nette que prévu pendant un moment; c'est très
//  différent d'une révélation qui s'arrête.
//
//  LES OCTETS NE VONT PAS DANS LES PRÉFÉRENCES. Une couche pèse jusqu'à
//  plusieurs centaines de kilo-octets et il y en a jusqu'à cinq par photo. Chaque
//  couche est un fichier; l'index ne porte que des métadonnées.
//

import Foundation

/// Une couche en attente d'expédition.
struct RevealOutboxItem: Codable, Equatable, Identifiable {
    /// Identifiant du message porteur — celui de la couche 0, celui que le
    /// destinataire utilisera pour retrouver la bulle à réécrire.
    let revealId: String
    let peerKey: String
    let layerIndex: Int
    let layerCount: Int
    let totalMs: Int
    let filename: String
    let mime: String
    /// Échéance ABSOLUE. C'est le seul champ qui permet de survivre à un
    /// redémarrage: une durée relative repartirait de zéro à chaque lancement, et
    /// une révélation de 48 h ne se terminerait jamais.
    let dueAt: Date

    var id: String { "\(revealId)#\(layerIndex)" }

    func isDue(at now: Date = Date()) -> Bool { dueAt <= now }
}

@MainActor
final class RevealOutbox: ObservableObject {
    static let shared = RevealOutbox()

    /// Au-delà, on considère que l'expédition ne se fera jamais et on libère le
    /// disque. Sept jours est la révélation la plus longue; on garde une journée
    /// de marge pour un utilisateur qui n'ouvre pas l'app pendant le week-end.
    static let maxAge: TimeInterval = 8 * 24 * 3600

    @Published private(set) var pending: [RevealOutboxItem] = []

    /// Injecté: la file ne sait pas envoyer, elle sait quand. Ça la rend
    /// testable sans réseau — c'est tout l'intérêt de la séparer.
    var send: ((RevealOutboxItem, Data) -> Void)?

    /// Injecté aussi, et pour la même raison. Ce type est compilé DANS la cible
    /// de tests pour être testable sans app: il ne peut donc dépendre d'aucun
    /// service de l'app, journal compris. Le brancher au vrai journal est le
    /// travail de l'appelant.
    var onWarning: ((String) -> Void)?

    /// __PLAINTEXT_LEFTOVERS_2026_09_24__ Chiffrement au repos des couches et de
    /// l'index, injecté par l'app au lancement (`MediaCipher`) — ce fichier est
    /// aussi compilé seul dans la cible de tests. Avant: la dernière couche, soit
    /// la photo NETTE, restait en clair jusqu'à une semaine dans Application
    /// Support. `open` doit laisser passer l'ancien format en clair.
    nonisolated(unsafe) static var seal: ((Data) throws -> Data)?
    nonisolated(unsafe) static var open: ((Data) throws -> Data)?

    nonisolated private static func sealed(_ data: Data) throws -> Data { try seal?(data) ?? data }
    nonisolated private static func opened(_ data: Data) -> Data? {
        guard let open else { return data }
        return try? open(data)
    }

    private let directory: URL
    private let indexURL: URL
    private var isDraining = false

    init(directory: URL? = nil) {
        let base = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("RevealOutbox", isDirectory: true)
        self.directory = base
        self.indexURL = base.appendingPathComponent("index.json")
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        load()
    }

    // MARK: - Écriture

    /// Met en file les couches 1…N-1. La couche 0 n'entre JAMAIS ici: elle part
    /// immédiatement, par le chemin d'envoi normal, et c'est elle qui crée la
    /// bulle chez le destinataire.
    func enqueue(revealId: String, peerKey: String, filename: String, mime: String,
                 layers: [Data], totalSeconds: TimeInterval, startedAt: Date = Date()) {
        guard layers.count >= 2 else { return }
        let finalIndex = layers.count - 1
        for index in 1...finalIndex {
            let fraction = Double(index) / Double(finalIndex)
            let item = RevealOutboxItem(
                revealId: revealId, peerKey: peerKey,
                layerIndex: index, layerCount: layers.count,
                totalMs: Int(totalSeconds * 1000),
                filename: filename, mime: mime,
                dueAt: startedAt.addingTimeInterval(totalSeconds * fraction)
            )
            do {
                try Self.sealed(layers[index]).write(to: blobURL(for: item),
                                                     options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                pending.append(item)
            } catch {
                onWarning?("couche \(index) non écrite: \(error)")
            }
        }
        persist()
    }

    // MARK: - Vidange

    /// Envoie tout ce qui est échu. Idempotent et ré-entrant: appelable au
    /// lancement, au premier plan, et aussi souvent qu'on veut.
    func drain(now: Date = Date()) {
        guard !isDraining, let send else { return }
        isDraining = true
        defer { isDraining = false }

        collectGarbage(now: now)

        // Par ordre de couche: envoyer la 3 avant la 2 ferait reculer l'image
        // chez le destinataire, puisqu'une couche PLUS BASSE est ignorée à
        // l'arrivée. L'ordre est ce qui rend la progression monotone.
        for item in pending.filter({ $0.isDue(at: now) }).sorted(by: { $0.layerIndex < $1.layerIndex }) {
            guard let bytes = (try? Data(contentsOf: blobURL(for: item))).flatMap(Self.opened) else {
                remove(item)   // octets disparus: rien à renvoyer, on n'insiste pas
                continue
            }
            send(item, bytes)
            remove(item)
        }
        persist()
    }

    /// Purge ce qui est trop vieux pour arriver un jour. Sans ça, une révélation
    /// dont l'app n'est jamais rouverte garde ses octets à vie.
    private func collectGarbage(now: Date) {
        let stale = pending.filter { now.timeIntervalSince($0.dueAt) > Self.maxAge }
        for item in stale { remove(item) }
    }

    private func remove(_ item: RevealOutboxItem) {
        try? FileManager.default.removeItem(at: blobURL(for: item))
        pending.removeAll { $0.id == item.id }
    }

    /// Tout abandonner pour un message — le message a été supprimé, ou l'envoi
    /// initial a échoué et il n'y a plus de bulle à enrichir.
    func cancel(revealId: String) {
        for item in pending where item.revealId == revealId { remove(item) }
        persist()
    }

    // MARK: - Persistance

    private func blobURL(for item: RevealOutboxItem) -> URL {
        // L'identifiant peut contenir n'importe quoi; on ne le met pas tel quel
        // dans un nom de fichier.
        let safe = item.revealId.replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .replacingOccurrences(of: ".", with: "_")
        return directory.appendingPathComponent("\(safe)-\(item.layerIndex).bin")
    }

    private func persist() {
        do {
            let data = try JSONEncoder().encode(pending)
            try Self.sealed(data).write(to: indexURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            onWarning?("index non écrit: \(error)")
        }
    }

    private func load() {
        guard let data = (try? Data(contentsOf: indexURL)).flatMap(Self.opened),
              let items = try? JSONDecoder().decode([RevealOutboxItem].self, from: data) else { return }
        pending = items
    }
}
