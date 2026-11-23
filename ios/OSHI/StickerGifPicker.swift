//
//  StickerGifPicker.swift
//  OSHI
//
//  __LOCAL_GIF_LIBRARY_2026_09_13__ Le sélecteur de GIFs et d'autocollants.
//
//  DEUX ONGLETS SEULEMENT — GIFs et Autocollants. Il n'y a PAS d'onglet « GIPHY »:
//  les résultats de GIPHY arrivent DANS ces deux catégories, à la suite du paquet
//  local. Pour la personne qui l'utilise il n'y a qu'une bibliothèque; d'où vient
//  chaque vignette est un détail d'implémentation, pas une décision à lui confier.
//
//  L'ORDRE EST DÉLIBÉRÉ: GIPHY d'abord, le paquet local ensuite.
//
//  Ça n'a pas toujours été le cas et le renversement est le fruit d'un essai
//  réel: le paquet local passait devant parce qu'il est instantané et marche
//  hors ligne. Mais mis côte à côte, GIPHY gagne largement — ce sont de vrais
//  personnages animés, là où le paquet local est de la forme plate. Reléguer
//  ce qui est manifestement meilleur derrière trois mille vignettes revient à
//  le cacher.
//
//  Ce que le local garantit ne change pas pour autant: quand GIPHY est
//  injoignable ou que le quota horaire est épuisé, la grille ne se vide pas et
//  n'affiche aucune erreur — elle redevient simplement le paquet local, qui est
//  toujours là, tout de suite, sans réseau.
//
//  29/09: à la demande du propriétaire, les GIFs EMBARQUÉS mis en avant (`fc_`, `gp_`, `cc_`)
//  passent désormais devant GIPHY en direct, et la mention « Powered by
//  GIPHY » n'est plus affichée.
//
//  Point d'entrée, calqué sur `MediaPickerView(onMediaSelected:)`:
//      .sheet(isPresented: $showGifPicker) {
//          StickerGifPicker { attachment in sendMediaMessage(attachment) }
//      }
//

import SwiftUI

struct StickerGifPicker: View {
    let onSelected: (MediaManager.MediaAttachment) -> Void

    init(onSelected: @escaping (MediaManager.MediaAttachment) -> Void) {
        self.onSelected = onSelected
    }

    @Environment(\.dismiss) private var dismiss
    @StateObject private var library = LocalGifLibrary.shared

    @State private var kind: LocalPackKind = .gif
    @State private var category: String?
    @State private var query: String = ""
    @State private var isSending = false

    @State private var giphyItems: [GiphyItem] = []
    @State private var giphyLoading = false
    @State private var giphyPage = 0
    @State private var giphyExhausted = false
    /// Incrémenté à chaque chargement lancé. Seul le plus récent a le droit
    /// d'éteindre `giphyLoading` — sinon un chargement annulé qui se termine
    /// APRÈS le suivant éteindrait le drapeau du suivant, les cases réservées
    /// disparaîtraient puis reviendraient, et on aurait remplacé un saut par un
    /// clignotement.
    @State private var giphyGeneration = 0
    @State private var searchTask: Task<Void, Never>?

    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 8)]

    private var localResults: [LocalGifItem] {
        library.search(query, kind: kind, category: category)
    }

    /// GIPHY ne connaît pas nos catégories. Choisir une puce veut dire « je
    /// regarde le paquet local », donc on n'y mêle pas de résultats distants:
    /// sinon la catégorie sélectionnée mentirait sur ce qui est affiché.
    private var showsGiphy: Bool { category == nil }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $kind) {
                    ForEach(LocalPackKind.allCases, id: \.self) { k in
                        Text(k.title).tag(k)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.top, 8)
                // Une catégorie de GIFs n'existe pas forcément côté autocollants:
                // la garder en changeant d'onglet donnerait une grille vide.
                .onChange(of: kind) { _, _ in
                    category = nil
                    resetGiphy()
                    loadGiphy()
                }

                categoryRow
                grid
            }
            .navigationTitle("gif.picker.title".localizedOrFallback("GIFs & Stickers"))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query,
                        prompt: "gif.picker.search".localizedOrFallback("Search"))
            .onChange(of: query) { _, _ in
                resetGiphy()
                loadGiphy()
            }
            // À l'ouverture: sans ceci, aucun résultat distant n'apparaîtrait tant
            // qu'on n'a pas changé d'onglet ou tapé quelque chose.
            .task { if giphyItems.isEmpty { loadGiphy() } }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
            .overlay {
                if isSending {
                    ProgressView().scaleEffect(1.3)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(.ultraThinMaterial)
                }
            }
        }
    }

    // MARK: - Catégories

    private var categoryRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip("gif.category.all".localizedOrFallback("All"), value: nil)
                ForEach(library.categories(kind), id: \.self) { c in
                    chip(c, value: c)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
        }
    }

    private func chip(_ label: String, value: String?) -> some View {
        let selected = category == value
        return Button { category = value } label: {
            Text(label)
                .font(.scaled(14))
                .fontWeight(selected ? .semibold : .regular)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Capsule().fill(selected ? Color.accentColor : Color(.systemGray5)))
                .foregroundColor(selected ? .white : .primary)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    // MARK: - Une seule grille: GIPHY d'abord, le paquet local à la suite

    /// Combien de cases vides tenir pendant que la première page arrive.
    ///
    /// C'EST LA CORRECTION D'UN SAUT VISIBLE. GIPHY est en tête, mais au premier
    /// rendu la liste distante est vide: le paquet local occupait donc le haut
    /// de l'écran, puis les résultats s'inséraient DEVANT lui et tout descendait
    /// d'un coup. On voyait une seconde d'émojis, puis la grille se réorganisait
    /// sous le doigt.
    ///
    /// Réserver la place règle ça. Le nombre n'a pas à égaler la page entière
    /// (cinquante): il suffit qu'il couvre ce qui est VISIBLE, parce que seul ce
    /// qu'on regarde peut sembler bouger. Le reste de la page s'ajoute plus bas,
    /// hors du champ.
    private static let placeholderCount = 12

    /// Préfixes des GIFs embarqués qui mènent la grille.
    static let featuredPrefixes = ["fc_", "gp_", "cc_"]

    /// Dans l'ordre des préfixes (phrases OSHI, puis GIPHY, puis émojis), et
    /// dans l'ordre du paquet à l'intérieur de chacun — langue de l'appareil d'abord.
    private var featuredResults: [LocalGifItem] {
        Self.featuredPrefixes.flatMap { prefix in localResults.filter { $0.id.hasPrefix(prefix) } }
    }

    private var otherLocalResults: [LocalGifItem] {
        localResults.filter { item in !Self.featuredPrefixes.contains { item.id.hasPrefix($0) } }
    }

    /// GIPHY en direct, sans ceux déjà embarqués (`gp_<id GIPHY>`).
    private var liveGiphyItems: [GiphyItem] {
        let bundled = Set(featuredResults.map(\.id))
        return giphyItems.filter { !bundled.contains("gp_" + $0.id) }
    }

    private var showsPlaceholders: Bool {
        showsGiphy && giphyItems.isEmpty && giphyLoading
    }

    /// __O_BUBBLE_MASCOT_2026_11_16__ The twenty O-bubble mascots, first in
    /// both tabs, offline. Shown with no category or the "OSHI" chip, and
    /// filtered by the same search (name, label, emoji, aliases, VoiceOver
    /// name in the user's language).
    private var mascotResults: [OSHIMascot] {
        guard category == nil || category == "OSHI" else { return [] }
        let q = query.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let present = OSHIMascotCatalog.all.filter { OSHIMascotCatalog.gifURL($0) != nil && OSHIMascotCatalog.isBundled($0) }
        guard !q.isEmpty else { return present }
        return present.filter { m in
            let names = [m.name, m.label, m.emoji, Self.mascotLabel(m)] + m.aliases
            return names.joined(separator: " ")
                .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil)
                .contains(q)
        }
    }

    static func mascotLabel(_ m: OSHIMascot) -> String {
        ReactionEmoji(rawValue: m.emoji)?.accessibilityLabel ?? m.label
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.scaled(13, weight: .semibold))
            .foregroundColor(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 4)
            .accessibilityAddTraits(.isHeader)
    }

    private func mascotCell(_ m: OSHIMascot) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6))
            MascotGlyph(mascot: m, side: 80, motion: .loop)
        }
        .frame(height: 96)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var grid: some View {
        ScrollView {
            if !mascotResults.isEmpty {
                LazyVGrid(columns: columns, spacing: 8) {
                    Section {
                        ForEach(mascotResults) { m in
                            Button { sendMascot(m) } label: { mascotCell(m) }
                                .buttonStyle(.plain)
                                .accessibilityLabel(Self.mascotLabel(m))
                                .accessibilityIdentifier("gif.mascot.\(m.name)")
                        }
                    } header: {
                        sectionHeader("OSHI")
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 8)
            }
            LazyVGrid(columns: columns, spacing: 8) {
                // __GIF_FEATURED_TOP_2026_09_29__ Les GIFs EMBARQUÉS mis en avant
                // (`fc_…` dessinés pour OSHI, `gp_…` venus de GIPHY, `cc_…` libres de droits) passent devant
                // tout, y compris GIPHY en direct: ils sont là à la première
                // image, sans réseau, et rien ne vient s'insérer au-dessus d'eux.
                ForEach(featuredResults) { item in
                    Button { send(item) } label: { localCell(item) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(item.tags.first ?? item.id)
                }
                if showsPlaceholders {
                    ForEach(0..<Self.placeholderCount, id: \.self) { _ in
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color(.systemGray6))
                            .frame(height: 96)
                            .accessibilityHidden(true)
                    }
                }
                if showsGiphy {
                    ForEach(liveGiphyItems) { item in
                        Button { sendGiphy(item) } label: { RemoteGifCell(url: item.previewURL) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(item.title.isEmpty ? "GIF" : item.title)
                            // La dernière vignette distante à l'écran demande la
                            // page suivante. Pas de bouton « voir plus »: on fait
                            // défiler un sélecteur de GIFs, on ne le pilote pas.
                            .onAppear {
                                if item.id == liveGiphyItems.last?.id { loadMoreGiphy() }
                            }
                    }
                }
                ForEach(otherLocalResults) { item in
                    Button { send(item) } label: { localCell(item) }
                        .buttonStyle(.plain)
                        .accessibilityLabel(item.tags.first ?? item.id)
                }
            }
            .padding(.horizontal)

            // Le tourniquet UNIQUEMENT pour les pages suivantes. Pendant la
            // première, ce sont les cases réservées qui disent « ça arrive »,
            // et en ajouter un second signal ferait bouger la grille pour rien.
            if giphyLoading && !giphyItems.isEmpty {
                ProgressView().padding(.vertical, 12)
            }

            if localResults.isEmpty && giphyItems.isEmpty && !giphyLoading && mascotResults.isEmpty {
                emptyState.padding(.top, 40)
            }
        }
    }

    @ViewBuilder
    private func localCell(_ item: LocalGifItem) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6))
            if let data = library.data(for: item) {
                // Rendu ANIMÉ: le chemin image habituel écraserait le GIF sur sa
                // première image (rééchantillonnage ImageIO).
                AnimatedImageView(data: data, contentMode: .scaleAspectFit).padding(4)
            }
        }
        .frame(height: 96)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.scaled(30)).foregroundColor(.secondary)
            Text("gif.picker.no_results".localizedOrFallback("Nothing here"))
                .font(.scaled(15)).foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Réseau

    /// Changer d'onglet ou de recherche RECOMMENCE la pagination. Sans ça, la
    /// page 2 d'une recherche viendrait se coller aux résultats d'une autre.
    private func resetGiphy() {
        giphyItems = []
        giphyPage = 0
        giphyExhausted = false
    }

    private func loadGiphy() {
        guard GiphyClient.isConfigured, !giphyExhausted else { return }
        searchTask?.cancel()
        let kindForGiphy: GiphyKind = (kind == .sticker) ? .sticker : .gif
        let term = query
        let page = giphyPage
        // `@MainActor` EXPLICITE: une `View` n'est pas isolée au fil principal en
        // mode Swift 5, donc une tâche non annotée écrirait `@State` depuis un fil
        // quelconque.
        giphyGeneration += 1
        let generation = giphyGeneration
        giphyLoading = true      // AVANT le saut de fil: les cases réservées
                                 // doivent exister dès la première image, sinon
                                 // le paquet local occupe le haut le temps d'un
                                 // aller-retour et c'est le saut qu'on corrige.
        searchTask = Task { @MainActor in
            // Enregistré TOUT DE SUITE, avant le moindre `await`: un `defer`
            // placé après une suspension ne s'exécute pas si l'on sort avant de
            // l'atteindre, et le drapeau resterait allumé pour toujours.
            defer { if generation == giphyGeneration { giphyLoading = false } }
            // Le délai sert à ne pas dépenser un appel par TOUCHE tapée — la clé
            // bêta plafonne à cent appels par heure pour toute l'application.
            // Il ne sert à rien quand personne n'a rien tapé: à l'ouverture,
            // attendre une demi-seconde ne fait que retarder ce que l'on est
            // venu voir. On n'attend donc que s'il y a une recherche en cours de
            // frappe, et jamais pour une page suivante, qui vient d'un
            // défilement.
            if page == 0 && !term.trimmingCharacters(in: .whitespaces).isEmpty {
                try? await Task.sleep(for: GiphyClient.searchDebounce)
                if Task.isCancelled { return }
            }
            // Un échec — quota épuisé, réseau coupé — ne vide rien et n'affiche
            // aucune erreur: la grille reste le paquet local, qui marche toujours.
            // Sans terme de recherche, on ne peut PAS se contenter de la
            // tendance: elle ne contient que ~174 éléments. `feed` mélange donc
            // tendance et termes larges pour que le défaut aille aussi loin
            // qu'une recherche.
            let items = (try? await (term.trimmingCharacters(in: .whitespaces).isEmpty
                ? GiphyClient.shared.feed(kindForGiphy, page: page)
                : GiphyClient.shared.search(term, kind: kindForGiphy,
                                            offset: page * GiphyClient.pageSize))) ?? []
            guard !Task.isCancelled else { return }
            // Une page vide veut dire qu'il n'y a plus rien: on arrête de
            // demander, sinon chaque défilement en bas de grille dépenserait un
            // appel pour rien. Une page INCOMPLÈTE ne suffit pas comme signal
            // dans le flux par défaut, où chaque page vient d'un terme différent.
            if items.isEmpty { giphyExhausted = true }
            let known = Set(giphyItems.map(\.id))
            giphyItems += items.filter { !known.contains($0.id) }
            giphyPage = page + 1
            if giphyPage >= GiphyClient.maxPages { giphyExhausted = true }
        }
    }

    /// Appelé quand la dernière vignette distante apparaît à l'écran.
    private func loadMoreGiphy() {
        guard !giphyLoading, !giphyExhausted, showsGiphy else { return }
        loadGiphy()
    }

    // MARK: - Envoi

    private func send(_ item: LocalGifItem) {
        guard !isSending, let data = library.data(for: item) else { return }
        isSending = true
        Haptics.tap(.light)
        Task { @MainActor in
            defer { isSending = false }
            await finish(data, fileName: item.fileName, source: "local-pack")
        }
    }

    /// Sends the pack's 240 px GIF through the normal GIF path: every
    /// platform and every old version receives an ordinary animated GIF.
    private func sendMascot(_ m: OSHIMascot) {
        guard !isSending, let url = OSHIMascotCatalog.gifURL(m),
              let data = try? Data(contentsOf: url) else { return }
        isSending = true
        Haptics.tap(.light)
        Task { @MainActor in
            defer { isSending = false }
            await finish(data, fileName: "oshi-\(m.name).gif", source: "oshi-mascot")
        }
    }

    private func sendGiphy(_ item: GiphyItem) {
        guard !isSending else { return }
        isSending = true
        Haptics.tap(.light)
        Task { @MainActor in
            defer { isSending = false }
            guard let data = try? await GiphyClient.shared.download(item.sendURL) else { return }
            await finish(data, fileName: "giphy-\(item.id).gif", source: "giphy")
        }
    }

    @MainActor
    private func finish(_ data: Data, fileName: String, source: String) async {
        do {
            // `processGIF` ne RÉENCODE pas: compresser un GIF l'aplatit.
            let attachment = try await MediaManager().processGIF(
                data, fileName: fileName, source: source)
            onSelected(attachment)
            dismiss()
        } catch {
            OshiLog.storage.info("🎬 StickerGifPicker: could not prepare \(fileName): \(error.localizedDescription)")
        }
    }
}

/// Vignette distante: on télécharge les octets et on les ANIME.
/// `AsyncImage` n'anime pas un GIF — il en affiche la première image, ce qui
/// donnerait une grille de vignettes figées au milieu des nôtres.
private struct RemoteGifCell: View {
    let url: URL
    @State private var data: Data?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color(.systemGray6))
            if let data {
                AnimatedImageView(data: data, contentMode: .scaleAspectFill)
            } else {
                ProgressView().scaleEffect(0.7)
            }
        }
        .frame(height: 96)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .task(id: url) {
            // Les vignettes viennent du CDN: ce sont des fichiers, pas des appels
            // au point d'accès, donc elles ne consomment pas le quota horaire.
            data = try? await GiphyClient.shared.download(url)
        }
    }
}
