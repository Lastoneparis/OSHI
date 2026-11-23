//
//  LocationSharingView.swift
//  OSHI
//
//  Location sharing UI components (WhatsApp-style)
//  ✅ FULLY LOCALIZED
//

import SwiftUI
import MapKit
import CoreLocation

// MARK: - Brand Color
private let appBrandColor = Theme.brand

// MARK: - Layout
/// Cap content width on wide screens (iPad / landscape) so forms & lists
/// don't stretch edge-to-edge; SE-safe because it only constrains the max.
private let locationContentMaxWidth: CGFloat = 560

// MARK: - Map Opener (Settings › Maps "Open locations with")

/// THE single place that opens a specific location or address, honouring the
/// user's choice in Settings › Maps (`mapOpenPreference`: "oshi" | "apple" |
/// "google").
///
/// 🔧 FIX (2026-09-23, "I picked Apple Maps and it still opens OSHI's map"):
/// the preference was only read by the 1:1 shared-location bubble and the
/// group full-map panel. Check-ins always forced Apple Maps, the group bubble
/// always opened OSHI's panel, map links in messages (maps.apple.com /
/// Google Maps) always went to Safari/Apple Maps, and street addresses in a
/// message weren't tappable at all. Every "open this place" action now goes
/// through here. The Map TAB stays OSHI's own map — it is a feature, not a
/// place being opened.
enum MapOpener {
    static let preferenceKey = "mapOpenPreference"

    /// Custom scheme used by `ClickableTextView` for street addresses it
    /// detects in a message. Never leaves the app.
    static let addressScheme = "oshi-address"

    static var preference: String {
        UserDefaults.standard.string(forKey: preferenceKey) ?? "oshi"
    }

    /// true when the preference sends places to Apple/Google Maps.
    static var opensExternally: Bool { preference == "apple" || preference == "google" }

    // MARK: Coordinates

    /// Opens `coordinate` with the preferred app. `oshi` is called for the
    /// OSHI choice when the caller has its own, richer presentation (contact
    /// avatar, live tracking…); otherwise OSHI's map is presented on top of
    /// the current screen, focused on the point.
    static func open(coordinate: CLLocationCoordinate2D, name: String? = nil, oshi: (() -> Void)? = nil) {
        let lat = coordinate.latitude, lon = coordinate.longitude
        switch preference {
        case "apple":
            let item = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
            item.name = name ?? "location.shared_location".localized
            item.openInMaps(launchOptions: nil)
        case "google":
            openGoogle(appURL: "comgooglemaps://?q=\(lat),\(lon)&center=\(lat),\(lon)",
                       webURL: "https://www.google.com/maps/search/?api=1&query=\(lat),\(lon)")
        default:
            if let oshi { oshi() } else { presentOshiMap(focus: coordinate, name: name) }
        }
    }

    // MARK: Addresses

    /// Opens a free-text street address with the preferred app. For OSHI the
    /// address is geocoded first (falls back to Apple Maps if it can't be).
    static func open(address: String) {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "&=?+#")
        let q = trimmed.addingPercentEncoding(withAllowedCharacters: allowed) ?? trimmed
        switch preference {
        case "apple":
            if let url = URL(string: "https://maps.apple.com/?q=\(q)") { UIApplication.shared.open(url) }
        case "google":
            openGoogle(appURL: "comgooglemaps://?q=\(q)",
                       webURL: "https://www.google.com/maps/search/?api=1&query=\(q)")
        default:
            CLGeocoder().geocodeAddressString(trimmed) { placemarks, _ in
                DispatchQueue.main.async {
                    if let coord = placemarks?.first?.location?.coordinate {
                        presentOshiMap(focus: coord, name: trimmed)
                    } else if let url = URL(string: "https://maps.apple.com/?q=\(q)") {
                        UIApplication.shared.open(url)
                    }
                }
            }
        }
    }

    // MARK: Links in messages

    /// Routes a tapped URL if it points at a place (maps.apple.com, Google
    /// Maps, `geo:`, or a detected address). Returns false for any other URL,
    /// which the caller opens as before.
    @discardableResult
    static func handleLink(_ url: URL) -> Bool {
        if url.scheme == addressScheme {
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: false)
            if let a = comps?.queryItems?.first(where: { $0.name == "q" })?.value { open(address: a) }
            return true
        }
        guard let target = mapTarget(from: url) else { return false }
        switch target {
        case .coordinate(let c, let name): open(coordinate: c, name: name)
        case .address(let a): open(address: a)
        }
        return true
    }

    enum Target { case coordinate(CLLocationCoordinate2D, String?), address(String) }

    /// Parses Apple Maps / Google Maps / geo: links. Only links that NAME a
    /// place are recognised — a bare maps.google.com or a short link (which
    /// can't be resolved offline) is left alone.
    static func mapTarget(from url: URL) -> Target? {
        let scheme = url.scheme?.lowercased() ?? ""
        let host = url.host?.lowercased() ?? ""
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ k: String) -> String? {
            items.first(where: { $0.name == k })?.value?.trimmingCharacters(in: .whitespaces)
        }
        func coord(_ s: String?) -> CLLocationCoordinate2D? {
            guard let s else { return nil }
            let parts = s.split(separator: ",").map { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard parts.count >= 2, let lat = parts[0], let lon = parts[1],
                  (-90...90).contains(lat), (-180...180).contains(lon) else { return nil }
            return CLLocationCoordinate2D(latitude: lat, longitude: lon)
        }
        func target(_ s: String?, name: String? = nil) -> Target? {
            guard let s, !s.isEmpty else { return nil }
            if let c = coord(s) { return .coordinate(c, name) }
            return .address(s)
        }

        if scheme == "geo" {
            // geo:lat,lon or geo:0,0?q=address
            let body = url.absoluteString.dropFirst(4).split(separator: "?").first.map(String.init)
            if let q = value("q") { return target(q) }
            return coord(body).map { .coordinate($0, nil) }
        }
        guard scheme == "http" || scheme == "https" else { return nil }

        if host == "maps.apple.com" || host == "maps.apple" {
            let name = value("q")
            return target(value("ll"), name: name) ?? target(value("daddr")) ?? target(value("address"))
                ?? target(value("q")) ?? target(value("sll"))
        }
        let isGoogle = host == "maps.google.com"
            || ((host == "google.com" || host.hasSuffix(".google.com") || host.hasPrefix("google.") || host.contains(".google."))
                && url.path.hasPrefix("/maps"))
        if isGoogle {
            if let t = target(value("query")) ?? target(value("q")) ?? target(value("destination"))
                ?? target(value("daddr")) ?? target(value("ll")) { return t }
            // /maps/@48.85,2.35,15z  or  /maps/place/Name/@48.85,2.35,15z
            if let at = url.path.range(of: "/@") {
                return coord(String(url.path[at.upperBound...])).map { .coordinate($0, nil) }
            }
            if let r = url.path.range(of: "/maps/place/") {
                let rest = url.path[r.upperBound...].split(separator: "/").first.map(String.init)
                return target(rest?.replacingOccurrences(of: "+", with: " "))
            }
        }
        return nil
    }

    // MARK: Private

    /// Google Maps app when installed (needs `comgooglemaps` in
    /// LSApplicationQueriesSchemes), Google Maps on the web otherwise. Opening
    /// the app URL directly and falling back on failure also covers a build
    /// whose Info.plist lacks the scheme.
    private static func openGoogle(appURL: String, webURL: String) {
        let web = URL(string: webURL)
        guard let app = URL(string: appURL) else {
            if let web { UIApplication.shared.open(web) }
            return
        }
        UIApplication.shared.open(app, options: [:]) { ok in
            if !ok, let web { UIApplication.shared.open(web) }
        }
    }

    /// OSHI's map, focused on the point. Presented through a SwiftUI
    /// `fullScreenCover` hosted by a transparent controller — the same path the
    /// shared-location bubble uses. Presenting `OfflineMapView` directly in a
    /// UIHostingController broke both its `dismiss()` (back button did nothing)
    /// and its focus (it opened on the user's position).
    private static func presentOshiMap(focus: CLLocationCoordinate2D, name _: String?) {
        guard let top = topViewController() else { return }
        let host = UIHostingController(rootView: OshiMapFocusHost(focus: focus))
        host.modalPresentationStyle = .overFullScreen
        host.view.backgroundColor = .clear
        top.present(host, animated: false)
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first
        let window = scene?.windows.first(where: { $0.isKeyWindow })
            ?? scene?.windows.first(where: { $0.windowLevel == .normal && !$0.isHidden })
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed { top = presented }
        return top
    }
}

// MARK: - Location Share Sheet

struct LocationShareSheet: View {
    @Environment(\.dismiss) var dismiss
    @StateObject private var locationManager = LocationSharingManager.shared
    
    let onShareLocation: (LocationMessage) -> Void
    let conversationId: String
    
    @State private var isLoadingLocation = false
    @State private var currentAddress: String?
    @State private var showPermissionRequest = false
    @State private var showAlwaysPermissionAlert = false
    @State private var showUpgradeToFullAccessAlert = false
    @State private var pendingLiveDuration: LocationSharingManager.LiveDuration?
    @State private var isCheckingPermission = false
    /// __LOCATION_PING_2026_10_09__ Drives the "pick a place" map.
    @State private var showPingPicker = false
    
    /// Le nom du destinataire, quand l'appelant peut le fournir. Partager sa
    /// position est l'une des rares actions d'une messagerie qu'on ne veut pas
    /// faire à la mauvaise personne: le dire en titre coûte une ligne.
    var recipientName: String = ""

    // MARK: - Corps
    //
    // __LOCATION_SHEET_2026_08_21__ Refonte demandée par Hugo.
    //
    // Ce que faisait l'écran d'avant, et pourquoi ça gênait:
    //
    //  • Le partage EN DIRECT demandait au minimum trois gestes — ouvrir la
    //    ligne, attendre une SECONDE feuille, choisir la durée. Une feuille
    //    par-dessus une feuille pour choisir entre six nombres.
    //  • Les deux actions étaient deux lignes de `List` d'aspect identique.
    //    Rien ne disait laquelle est celle qu'on veut neuf fois sur dix.
    //  • Un `List` avec deux éléments, ses séparateurs et ses marges, pour deux
    //    choix: beaucoup d'habillage pour peu de contenu.
    //  • L'écran ne disait jamais À QUI on partage.
    //  • Un partage déjà en cours n'apparaissait pas: pour l'arrêter il fallait
    //    le chercher ailleurs.
    //
    // Ce qui change: la carte porte l'adresse en surimpression au lieu d'une
    // ligne séparée, l'envoi immédiat devient un bouton plein — l'action
    // principale ressemble à l'action principale — et les six durées sont des
    // pastilles posées à plat. Choisir « 15 minutes » est UN geste, la feuille
    // imbriquée disparaît. Si un partage est actif, il prend la place des
    // pastilles avec son heure de fin et son bouton d'arrêt.
    //
    // Zéro clé de traduction nouvelle: tout ce qui est affiché existait déjà
    // dans les 34 langues. Une refonte qui aurait ajouté cinq clés aurait ajouté
    // cent soixante-dix chaînes à écrire, et le risque d'en livrer une partie en
    // anglais.

    private var isSharingLiveHere: Bool {
        locationManager.liveShare(for: conversationId) != nil
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 20) {
                    mapCard

                    if !locationManager.isAuthorized {
                        permissionCard
                    } else if isSharingLiveHere {
                        activeLiveCard
                    } else {
                        sendNowButton
                        pickOnMapButton
                        liveSection
                    }
                }
                .padding(.horizontal)
                .padding(.bottom, 24)
                .frame(maxWidth: locationContentMaxWidth)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle(recipientName.isEmpty ? "location.share".localized : recipientName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("common.cancel".localized) { dismiss() }
                }
            }
            .onAppear { checkPermissionAndLoadLocation() }
            .sheet(isPresented: $showPingPicker) {
                LocationPingPickerView(
                    onSendPing: { ping in
                        // Same callback as a shared position: a ping is an
                        // ordinary one-shot location, so it needs no new path.
                        onShareLocation(ping)
                        dismiss()
                    },
                    recipientName: recipientName
                )
            }
            .sheet(isPresented: $showPermissionRequest) {
                LocationPermissionRequestView(
                    onPermissionGranted: { loadCurrentLocation() },
                    onDismiss: { }
                )
                .presentationDetents([.medium, .large])
            }
            .onChange(of: locationManager.authorizationStatus) { _, newStatus in
                if newStatus == .authorizedWhenInUse || newStatus == .authorizedAlways {
                    loadCurrentLocation()
                }
                if newStatus == .authorizedAlways && pendingLiveDuration != nil {
                    proceedWithLiveLocation()
                }
            }
            .alert("location.live_permission_title".localized, isPresented: $showAlwaysPermissionAlert) {
                Button("location.allow_always".localized) { locationManager.openAppLocationSettings() }
                Button("location.keep_while_using".localized) { proceedWithLiveLocation() }
                Button("common.cancel".localized, role: .cancel) { pendingLiveDuration = nil }
            } message: {
                Text("location.live_permission_upgrade".localized)
            }
            .alert("location.partial_access_title".localized, isPresented: $showUpgradeToFullAccessAlert) {
                Button("location.upgrade_to_always".localized) { locationManager.openAppLocationSettings() }
                Button("location.continue_with_partial".localized) { loadCurrentLocation() }
                Button("common.cancel".localized, role: .cancel) { dismiss() }
            } message: {
                Text("location.partial_access_message".localized)
            }
        }
    }

    // MARK: - La carte, et l'adresse posée dessus

    @ViewBuilder
    private var mapCard: some View {
        ZStack(alignment: .bottomLeading) {
            if let location = locationManager.currentLocation {
                LocationMapPreview(coordinate: location.coordinate,
                                   accuracy: location.horizontalAccuracy)
            } else {
                ZStack {
                    Rectangle().fill(Color(.systemGray5))
                    if isLoadingLocation || isCheckingPermission {
                        VStack(spacing: 10) {
                            ProgressView()
                            Text(isCheckingPermission
                                 ? "location.checking_permission".localized
                                 : "location.updating".localized)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    } else {
                        Image(systemName: "location.slash")
                            .font(.largeTitle)
                            .foregroundColor(.secondary)
                    }
                }
            }

            // L'adresse EN SURIMPRESSION plutôt qu'une ligne à part: elle décrit
            // la carte, elle n'est pas une information de plus.
            //
            // __LOCATION_MAP_CAMERA_2026_08_22__ Et sous l'adresse, ce que la
            // carte seule ne dit pas: les coordonnées envoyées et la PRÉCISION du
            // relevé. Un point posé sur une carte a l'air exact au mètre; « ± 65 m »
            // est parfois la vérité, et c'est une information qui compte avant
            // d'envoyer sa position à quelqu'un qui va s'y rendre.
            //
            // Aucune clé de traduction nouvelle: des nombres, et une unité rendue
            // par `MeasurementFormatter` — donc « m » ou « ft » selon la région du
            // téléphone, sans qu'on ait à l'écrire nulle part.
            if let location = locationManager.currentLocation {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "mappin.circle.fill")
                        .foregroundColor(appBrandColor)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        if let address = currentAddress, !address.isEmpty {
                            Text(address)
                                .font(.footnote.weight(.medium))
                                .foregroundColor(.primary)
                                .lineLimit(2)
                        }
                        Text(coordinateDetail(for: location))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .oledAwareBackground(.regularMaterial, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .padding(10)
                .accessibilityElement(children: .combine)
            }
        }
        .frame(height: 240)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        // Un liseré: en mode sombre la carte et le fond de l'écran ont
        // sensiblement la même valeur, et le bord de la vignette disparaissait.
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
        )
    }

    // MARK: - Action principale

    private var sendNowButton: some View {
        Button {
            shareCurrentLocation()
        } label: {
            HStack(spacing: 10) {
                if isLoadingLocation {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: "location.fill")
                }
                Text("location.share_current".localized)
                    .font(.system(.headline, design: .rounded))
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 15)
            .background(appBrandColor, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .disabled(isLoadingLocation || locationManager.currentLocation == nil)
        .opacity(locationManager.currentLocation == nil ? 0.5 : 1)
        .accessibilityLabel("location.share_current".localized)
        .accessibilityHint("location.share_current_hint".localized)
    }

    // MARK: - Ping: un lieu qui n'est pas le mien  __LOCATION_PING_2026_10_09__
    //
    // Secondaire, sous l'action principale: envoyer SA position reste le geste
    // courant. Mais « on se retrouve ICI » n'était pas possible du tout — la
    // carte de cette feuille est construite avec `interactionModes: []`, donc
    // la seule coordonnée qu'on pouvait envoyer était la sienne.
    //
    // Deliberately in the same sheet rather than a new entry in the attachment
    // menu: the user already looked for "location" and found it, and a second
    // top-level item for the same idea is the mistake the two call-privacy
    // controls made.

    private var pickOnMapButton: some View {
        Button {
            showPingPicker = true
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "mappin.and.ellipse")
                Text("location.ping.pick".localized)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.secondary)
            }
            .foregroundColor(appBrandColor)
            .padding(.vertical, 14)
            .padding(.horizontal, 14)
            .background(appBrandColor.opacity(0.12),
                        in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .accessibilityLabel("location.ping.pick".localized)
        .accessibilityHint("location.ping.pick_hint".localized)
    }

    // MARK: - En direct: une pastille = un partage

    private var liveSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            // __LOCATION_SHEET_2026_08_22__ Le bloc « en direct » devient une
            // CARTE, comme celle qui le remplace quand un partage est en cours.
            // L'écran se lit alors en trois temps — la carte, l'action
            // principale, le bloc secondaire — au lieu de deux listes de contrôles
            // posées l'une sous l'autre sur le même fond.
            HStack(spacing: 7) {
                Image(systemName: "dot.radiowaves.left.and.right")
                    .font(.footnote)
                    .foregroundColor(appBrandColor)
                    .accessibilityHidden(true)
                Text("location.share_live".localized)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    .foregroundColor(.primary)
            }
            Text("location.live_duration".localized)
                .font(.caption)
                .foregroundColor(.secondary)

            // Grille souple: six durées tiennent sur deux rangs sur tout iPhone,
            // et s'étalent sur un seul sur iPad. Pas de largeur en dur — c'est
            // ce genre de nombre qui déborde des écrans étroits.
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 8) {
                ForEach(LocationSharingManager.LiveDuration.allCases, id: \.self) { duration in
                    Button {
                        shareLiveLocation(duration: duration)
                    } label: {
                        // __PILL_CONTRAST_2026_08_22__ « 2 minutes », « 15 min »
                        // étaient illisibles. La marque est un violet PROFOND —
                        // RGB(0.337, 0.086, 0.925), luminance ~0.06 — et la
                        // pastille l'employait pour le texte ET pour son fond à
                        // 12 %. En clair ça passe; en SOMBRE, ce violet foncé se
                        // pose sur un noir à peine teinté du même violet: texte et
                        // fond ont quasiment la même luminance, et il n'y a plus
                        // rien à lire.
                        //
                        // Le libellé passe donc en `.primary` — blanc en sombre,
                        // noir en clair, contraste garanti des deux côtés — et la
                        // marque reste présente comme TEINTE de la capsule et de
                        // son liseré. C'est aussi ce qu'il fallait pour la
                        // hiérarchie: le seul élément qui porte la couleur pleine
                        // est le bouton d'envoi, l'action principale.
                        Text(duration.displayName)
                            .font(.footnote.weight(.medium))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .foregroundColor(.primary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(appBrandColor.opacity(0.22), in: Capsule())
                            .overlay(Capsule().stroke(appBrandColor.opacity(0.45), lineWidth: 1))
                    }
                    .accessibilityLabel("\("location.share_live".localized) — \(duration.displayName)")
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: - Un partage déjà en cours

    private var activeLiveCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Circle().fill(Color.green).frame(width: 9, height: 9)
                Text("location.sharing_live".localized)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                Spacer()
            }
            if let end = locationManager.liveShare(for: conversationId)?.endTime {
                Text("\("location.expires_in".localized) \(end.formatted(date: .omitted, time: .shortened))")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Button {
                locationManager.stopLiveSharing(conversationId: conversationId)
            } label: {
                Text("location.stop_sharing".localized)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    .foregroundColor(.red)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.red.opacity(0.12),
                                in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
        .padding(14)
        .background(Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    // MARK: - Autorisation: un seul état, pas trois

    private var permissionCard: some View {
        VStack(spacing: 12) {
            Image(systemName: "location.slash")
                .font(.title)
                .foregroundColor(appBrandColor)
                .accessibilityHidden(true)
            Text("location.enable_location".localized)
                .font(.system(.headline, design: .rounded))
            Text("location.enable_location_desc".localized)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Button {
                showPermissionRequest = true
            } label: {
                Text("location.request_again".localized)
                    .font(.system(.subheadline, design: .rounded).weight(.semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(appBrandColor, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityHint("location.enable_location_hint".localized)
    }

    /// « 48.85661, 2.35222 · ± 12 m ». Les coordonnées gardent le point décimal
    /// — c'est la convention géographique, pas un nombre à localiser — et la
    /// précision passe par `MeasurementFormatter`, qui choisit mètres ou pieds
    /// selon la région de l'appareil. Une précision absente ou négative (iOS le
    /// fait quand le relevé n'est pas fiable) n'affiche rien plutôt qu'un
    /// « ± -1 m ».
    private func coordinateDetail(for location: CLLocation) -> String {
        let coords = String(format: "%.5f, %.5f", location.coordinate.latitude, location.coordinate.longitude)
        guard location.horizontalAccuracy > 0 else { return coords }
        let formatter = MeasurementFormatter()
        formatter.unitOptions = .naturalScale
        formatter.numberFormatter.maximumFractionDigits = 0
        let radius = Measurement(value: location.horizontalAccuracy, unit: UnitLength.meters)
        return "\(coords) · ± \(formatter.string(from: radius))"
    }

    private func checkPermissionAndLoadLocation() {
        let status = locationManager.authorizationStatus
        
        switch status {
        case .authorizedAlways:
            // Full access - proceed normally
            loadCurrentLocation()
        case .authorizedWhenInUse:
            // Partial access - show upgrade prompt
            showUpgradeToFullAccessAlert = true
        case .notDetermined:
            // No permission yet - will show request when user taps button
            break
        case .denied, .restricted:
            // Denied - will show permission request to guide to settings
            break
        @unknown default:
            break
        }
    }
    
    private func loadCurrentLocation() {
        isLoadingLocation = true
        
        locationManager.getCurrentLocation { result in
            isLoadingLocation = false
            
            switch result {
            case .success(let location):
                // Get address
                GeocoderHelper.shared.getAddress(for: location) { address in
                    currentAddress = address
                }
            case .failure(let error):
                OshiLog.maps.info("❌ Failed to get location: \(error)")
            }
        }
    }
    
    private func shareCurrentLocation() {
        guard let location = locationManager.currentLocation else {
            loadCurrentLocation()
            return
        }
        
        let message = LocationMessage.oneTime(from: location, address: currentAddress)
        onShareLocation(message)
        dismiss()
    }
    
    private func shareLiveLocation(duration: LocationSharingManager.LiveDuration) {
        guard let location = locationManager.currentLocation else { return }
        
        // Check if we need Always permission for background updates
        if locationManager.needsAlwaysPermissionUpgrade {
            // Show alert to explain why we need Always permission
            pendingLiveDuration = duration
            showAlwaysPermissionAlert = true
            return
        }
        
        // If we don't have Always but have WhenInUse, still proceed but warn
        // The location updates will only work while app is in foreground
        
        // Generate a unique session ID for this live share
        let sessionId = UUID().uuidString
        
        let message = LocationMessage.live(from: location, duration: duration, address: currentAddress, sessionId: sessionId)
        onShareLocation(message)
        
        // Start live sharing with the session ID
        locationManager.startLiveSharing(duration: duration, conversationId: conversationId, messageId: sessionId, onUpdate: { updatedLocation in
            // Updates use liveUpdate (silent - no notification spam)
            let updateMessage = LocationMessage.liveUpdate(from: updatedLocation, duration: duration, sessionId: sessionId)
            // Send update through callback (silently)
            onShareLocation(updateMessage)
        }, onStop: {
            // Timer expired or stopped → tell the peer so their bubble stops live too.
            onShareLocation(LocationMessage.stopped(sessionId: sessionId))
        })

        dismiss()
    }

    private func proceedWithLiveLocation() {
        guard let duration = pendingLiveDuration,
              let location = locationManager.currentLocation else { return }
        
        // Generate a unique session ID for this live share
        let sessionId = UUID().uuidString
        
        let message = LocationMessage.live(from: location, duration: duration, address: currentAddress, sessionId: sessionId)
        onShareLocation(message)
        
        // Start live sharing with the session ID
        locationManager.startLiveSharing(duration: duration, conversationId: conversationId, messageId: sessionId, onUpdate: { updatedLocation in
            // Updates use liveUpdate (silent - no notification spam)
            let updateMessage = LocationMessage.liveUpdate(from: updatedLocation, duration: duration, sessionId: sessionId)
            onShareLocation(updateMessage)
        }, onStop: {
            onShareLocation(LocationMessage.stopped(sessionId: sessionId))
        })

        pendingLiveDuration = nil
        dismiss()
    }
}

// MARK: - Live Location Duration Picker

/// __LOCATION_MAP_CAMERA_2026_08_22__ « on ne voit rien autour du point, c'est
/// une seule couleur ».
///
/// LA CAUSE
/// `region` était construit dans l'init… et n'était RELIÉ À RIEN. `Map { }` sans
/// `position:` s'ouvre en `.automatic`, c'est-à-dire cadré par MapKit sur le
/// contenu — ici une annotation ponctuelle, donc un cadrage arbitraire, souvent
/// si serré qu'il ne reste à l'écran qu'un aplat de la couleur du sol, sans une
/// rue ni un nom. Le `MKCoordinateSpan(0.01)` écrit juste au-dessus n'a jamais
/// été appliqué une seule fois.
///
/// CE QUI CHANGE
/// La caméra est explicitement posée à 700 m de côté — l'échelle où les rues et
/// les commerces sont lisibles — et les points d'intérêt sont demandés
/// explicitement plutôt que laissés au style par défaut. Le halo optionnel dessine
/// la PRÉCISION réelle du relevé: un point seul laisse croire au mètre près.
struct LocationMapPreview: View {
    let coordinate: CLLocationCoordinate2D
    /// Précision horizontale du relevé, en mètres. `nil` ou négatif → pas de halo.
    var accuracy: CLLocationAccuracy? = nil

    /// Côté de la fenêtre cartographique, en mètres. Assez large pour porter un
    /// nom de rue, assez serré pour qu'on reconnaisse l'endroit.
    private static let spanMeters: CLLocationDistance = 700

    @State private var camera: MapCameraPosition

    init(coordinate: CLLocationCoordinate2D, accuracy: CLLocationAccuracy? = nil) {
        self.coordinate = coordinate
        self.accuracy = accuracy
        _camera = State(initialValue: .region(MKCoordinateRegion(
            center: coordinate,
            latitudinalMeters: Self.spanMeters,
            longitudinalMeters: Self.spanMeters
        )))
    }

    var body: some View {
        Map(position: $camera, interactionModes: []) {
            if let accuracy, accuracy > 0 {
                MapCircle(center: coordinate, radius: accuracy)
                    .foregroundStyle(appBrandColor.opacity(0.12))
                    .stroke(appBrandColor.opacity(0.45), lineWidth: 1)
            }
            Annotation("", coordinate: coordinate) {
                ZStack {
                    Circle()
                        .fill(appBrandColor.opacity(0.3))
                        .frame(width: 40, height: 40)

                    Circle()
                        .fill(appBrandColor)
                        .frame(width: 16, height: 16)
                        .overlay(Circle().stroke(Color.white, lineWidth: 2))
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, pointsOfInterest: .all, showsTraffic: false))
        // `CLLocationCoordinate2D` n'est pas `Equatable`: on observe le couple de
        // nombres, pas la structure.
        .onChange(of: coordinate.latitude) { _, _ in recenter() }
        .onChange(of: coordinate.longitude) { _, _ in recenter() }
        .accessibilityElement()
        .accessibilityLabel("location.map_preview".localized)
    }

    private func recenter() {
        withAnimation(.easeInOut(duration: 0.35)) {
            camera = .region(MKCoordinateRegion(
                center: coordinate,
                latitudinalMeters: Self.spanMeters,
                longitudinalMeters: Self.spanMeters
            ))
        }
    }
}

struct LocationAnnotation: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
}

// MARK: - Location Message Bubble

struct LocationMessageBubble: View {
    let locationMessage: LocationMessage
    let isFromCurrentUser: Bool
    var onSendLocation: ((LocationMessage) -> Void)? = nil
    var conversationId: String? = nil
    var contactName: String? = nil
    var contactPublicKey: String? = nil
    
    @ObservedObject private var locationManager = LocationSharingManager.shared
    /// __LOCATION_MAP_CAMERA_2026_08_22__ Était `@State private var region:
    /// MKCoordinateRegion` — un état que `fitBothLocations()` et
    /// `fitToOwnAnd(peerCoord:)` mettaient consciencieusement à jour, et que la
    /// carte NE LISAIT PAS: `Map { }` sans `position:` s'ouvre en `.automatic`.
    /// Les deux fonctions de cadrage ne déplaçaient donc rien du tout, et la
    /// vignette s'ouvrait sur un cadrage choisi par MapKit — parfois si serré
    /// qu'il n'y a plus qu'un aplat de couleur autour du point.
    @State private var camera: MapCameraPosition
    @State private var remainingTimeString: String?
    @State private var timer: Timer?
    @State private var myCurrentLocation: CLLocationCoordinate2D?
    @State private var showExtendOptions = false
    @State private var showShareOptions = false
    @State private var isLoadingMyLocation = false
    @State private var displayedCoordinate: CLLocationCoordinate2D  // Real-time updated coordinate
    @State private var locationUpdateTimer: Timer?
    @State private var refreshTrigger = false  // Force UI refresh
    @State private var showOshiMapFullScreen = false  // Map preference: show OSHI map
    @State private var isReceiverLiveSharing = false  // 🔧 NEW: Track receiver's live sharing
    @State private var receiverLiveSessionId: String? = nil  // 🔧 NEW: Receiver's session ID
    @State private var myLiveUpdateTimer: Timer? = nil  // 🔧 NEW: Timer for receiver's updates
    @State private var didFitToPeerLive: Bool = false   // 🔧 Bug 5: latch first auto-fit on Alice+Bob
    @Environment(\.colorScheme) var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    
    init(locationMessage: LocationMessage, isFromCurrentUser: Bool, onSendLocation: ((LocationMessage) -> Void)? = nil, conversationId: String? = nil, contactName: String? = nil, contactPublicKey: String? = nil) {
        self.locationMessage = locationMessage
        self.isFromCurrentUser = isFromCurrentUser
        self.onSendLocation = onSendLocation
        self.conversationId = conversationId
        self.contactName = contactName
        self.contactPublicKey = contactPublicKey
        _camera = State(initialValue: .region(MKCoordinateRegion(
            center: locationMessage.coordinate,
            latitudinalMeters: LocationMessageBubble.spanMeters,
            longitudinalMeters: LocationMessageBubble.spanMeters
        )))
        _displayedCoordinate = State(initialValue: locationMessage.coordinate)
    }

    /// Côté de la fenêtre cartographique par défaut, en mètres — l'échelle à
    /// laquelle une rue porte encore son nom. Le cadrage à deux points
    /// (`fitBothLocations`) l'élargit quand il le faut.
    fileprivate static let spanMeters: CLLocationDistance = 800

    /// The current user's own profile photo, for showing MY avatar on the map
    /// when I'm the one sharing. Reads the live IdentityManager first, then the
    /// persisted UserDefaults copy so it works even before the manager is wired.
    private var ownProfilePhoto: UIImage? {
        if let data = MessageManager.sharedIdentityManager?.profileImageData
            ?? UserDefaults.standard.data(forKey: "profileImageData"),
           let image = UIImage(data: data) {
            return image
        }
        return nil
    }

    /// Check if THIS message is the currently active live share session
    /// Not just any live message, but THE current one being shared
    private var isMyLiveShare: Bool {
        myLiveShare != nil
    }

    /// The running share THIS bubble belongs to. Several can run at once
    /// (__MULTI_LIVE_SHARE_2026_09_22__), so it is matched by sessionId, not by
    /// "the" current share.
    private var myLiveShare: LocationSharingManager.ActiveLiveShare? {
        guard isFromCurrentUser && locationMessage.isLive else { return nil }

        // Use sessionId for precise matching (new format)
        if let messageSessionId = locationMessage.sessionId {
            return locationManager.liveShare(sessionId: messageSessionId)
        }

        // Fallback to timestamp check for backward compatibility with old messages
        // (60 s tolerance — messages might be sent with slight delay)
        return locationManager.activeLiveShares.first {
            abs(locationMessage.timestamp.timeIntervalSince($0.startTime)) < 60
        }
    }
    
    /// Check if this is a live location that is still active (for display purposes)
    private var isActiveLiveLocation: Bool {
        locationMessage.isLive && !locationMessage.isExpired
    }
    
    // Use real-time coordinate for live shares from current user
    // 🔧 FIX: Always use locationMessage.coordinate for non-owner views to get updates
    private var effectiveCoordinate: CLLocationCoordinate2D {
        if isMyLiveShare, let currentLoc = locationManager.currentLocation {
            // For my own live share, use real-time location from LocationSharingManager
            return currentLoc.coordinate
        }
        // 🔧 FIX: Use locationMessage.coordinate directly (not displayedCoordinate)
        // This ensures the map updates when we receive location updates from the sender
        return locationMessage.coordinate
    }

    /// Bug 5: peer's live coordinate to overlay on the user's OWN active share.
    /// Non-nil only when:
    ///   - this bubble is the user's own active live share (isMyLiveShare → isFromCurrentUser)
    ///   - we have a contactPublicKey for the conversation peer
    ///   - the peer is currently back-sharing AND that share hasn't expired
    /// Returns nil otherwise so the third annotation is silently skipped.
    private var peerLiveCoordinate: CLLocationCoordinate2D? {
        guard isMyLiveShare,
              isFromCurrentUser,
              let peerKey = contactPublicKey,
              let entry = locationManager.liveContactLocations[peerKey],
              entry.expiresAt > Date()
        else { return nil }
        return entry.coordinate
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Map with both locations
            ZStack(alignment: .topTrailing) {
                Map(position: $camera, interactionModes: []) {
                    // Their location (or my sent location) - uses real-time coordinate for own live shares.
                    // 🔧 FIX (2026-06-04): when this bubble shows the OTHER side's
                    // location, render the sender's actual profile photo inside the
                    // dot instead of the generic "👤" emoji (WhatsApp-style).
                    Annotation("", coordinate: effectiveCoordinate) {
                        ZStack {
                            Circle()
                                .fill(appBrandColor.opacity(0.3))
                                .frame(width: 44, height: 44)

                            if locationMessage.isLive && !locationMessage.isExpired {
                                // Pulsing animation for live (respect Reduce Motion)
                                Circle()
                                    .stroke(appBrandColor, lineWidth: 2)
                                    .frame(width: 44, height: 44)
                                    .scaleEffect(reduceMotion ? 1.0 : 1.3)
                                    .opacity(0.5)
                            }

                            // Show a real avatar in the dot: the CONTACT's photo
                            // when viewing their share, or MY OWN photo when I'm
                            // the one sharing (previously my own share fell back
                            // to a plain "Me" dot with no picture).
                            if let photo = isFromCurrentUser
                                ? ownProfilePhoto
                                : contactPublicKey.flatMap({ ContactPhotoManager.shared.getPhoto(for: $0) }) {
                                Image(uiImage: photo)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 26, height: 26)
                                    .clipShape(Circle())
                                    .overlay(Circle().stroke(Color.white, lineWidth: 2))
                            } else {
                                Circle()
                                    .fill(appBrandColor)
                                    .frame(width: 18, height: 18)

                                Text(isFromCurrentUser ? "location.me".localized : "👤")
                                    .font(.scaled(10, weight: .bold))
                                    .foregroundColor(.white)
                            }
                        }
                    }

                    // My current location — always shown when viewing someone
                    // ELSE's share, regardless of whether I've tapped "share back".
                    // 🔧 FIX (2026-06-04): previously this annotation only rendered
                    // after the user explicitly hit Share My Location / Share Back.
                    // WhatsApp shows YOUR dot the moment you open someone's live
                    // location, with no extra interaction. Seeded in startTimers()
                    // from locationManager.currentLocation and kept in sync via
                    // .onReceive($currentLocation) below.
                    if let myLoc = myCurrentLocation, !isFromCurrentUser {
                        Annotation("", coordinate: myLoc) {
                            ZStack {
                                Circle()
                                    .fill(Color.blue.opacity(0.3))
                                    .frame(width: 40, height: 40)

                                Circle()
                                    .fill(Color.blue)
                                    .frame(width: 16, height: 16)
                                    .overlay(Circle().stroke(Color.white, lineWidth: 2))

                                Text("location.me".localized)
                                    .font(.scaled(8, weight: .bold))
                                    .foregroundColor(.white)
                            }
                        }
                    }

                    // 🔧 Bug 5: peer's dot on the user's OWN active live share.
                    // Symmetric to the receiver-side "My current location" block
                    // above. Only renders when isMyLiveShare AND we have a fresh
                    // back-share entry for the peer (gated inside peerLiveCoordinate).
                    // Profile-photo styling mirrors the sender annotation up top so
                    // the user instantly recognizes the peer.
                    if let peerCoord = peerLiveCoordinate, let pk = contactPublicKey {
                        Annotation("", coordinate: peerCoord) {
                            ZStack {
                                Circle()
                                    .fill(Color.blue.opacity(0.3))
                                    .frame(width: 44, height: 44)
                                Circle()
                                    .stroke(Color.blue, lineWidth: 2)
                                    .frame(width: 44, height: 44)
                                    .scaleEffect(reduceMotion ? 1.0 : 1.3)
                                    .opacity(0.5)
                                if let photo = ContactPhotoManager.shared.getPhoto(for: pk) {
                                    Image(uiImage: photo)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 26, height: 26)
                                        .clipShape(Circle())
                                        .overlay(Circle().stroke(Color.white, lineWidth: 2))
                                } else {
                                    Circle()
                                        .fill(Color.blue)
                                        .frame(width: 18, height: 18)
                                    Text("👤")
                                        .font(.scaled(10, weight: .bold))
                                        .foregroundColor(.white)
                                }
                            }
                        }
                    }
                }
                .mapStyle(.standard(elevation: .flat, pointsOfInterest: .all, showsTraffic: false))
                .frame(width: 280, height: 180)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                // `CLLocationCoordinate2D` n'est pas `Equatable`: on observe les
                // deux nombres. Un partage en direct qui bouge doit rester dans
                // le cadre — avant, la carte ne suivait rien puisqu'elle
                // n'écoutait aucun cadrage.
                .onChange(of: effectiveCoordinate.latitude) { _, _ in followMovingPoint() }
                .onChange(of: effectiveCoordinate.longitude) { _, _ in followMovingPoint() }
                .onTapGesture {
                    openInMaps()
                }
                .accessibilityElement()
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel(isFromCurrentUser ? "location.map_mine".localized : "location.map_theirs".localized)
                .accessibilityHint("location.open_in_maps".localized)

                // Live indicator badge
                if locationMessage.isLive && !locationMessage.isExpired {
                    HStack(spacing: 4) {
                        Circle()
                            .fill(appBrandColor)
                            .frame(width: 8, height: 8)
                            .accessibilityHidden(true)
                        Text("LIVE")
                            .font(.scaled(10, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.black.opacity(0.6))
                    .cornerRadius(10)
                    .padding(8)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("location.sharing_live".localized)
                }
            }
            
            // Info bar
            VStack(alignment: .leading, spacing: 6) {
                // Status row
                HStack(spacing: 6) {
                    if locationMessage.isLive && !locationMessage.isExpired {
                        Circle()
                            .fill(appBrandColor)
                            .frame(width: 8, height: 8)
                        Text("location.sharing_live".localized)
                            .font(.caption)
                            .fontWeight(.medium)
                            .foregroundColor(appBrandColor)
                    } else if locationMessage.isLive && locationMessage.isExpired {
                        Image(systemName: "clock.badge.xmark")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        Text("location.expired".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    } else {
                        Image(systemName: "mappin.circle.fill")
                            .font(.caption)
                            .foregroundColor(appBrandColor)
                        Text("location.shared_location".localized)
                            .font(.caption)
                            .foregroundColor(.primary)
                    }
                    
                    Spacer()
                    
                    // Remaining time
                    if locationMessage.isLive, let timeString = remainingTimeString {
                        Text(timeString)
                            .font(.caption2)
                            .fontWeight(.medium)
                            .foregroundColor(.orange)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.orange.opacity(0.15))
                            .cornerRadius(8)
                    }
                }
                
                // Address
                if let address = locationMessage.address {
                    Text(address)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .lineLimit(2)
                }
                
                // Action buttons
                if isMyLiveShare {
                    // SENDER: Stop / Extend buttons
                    HStack(spacing: 8) {
                        Button {
                            #if !targetEnvironment(macCatalyst)
                            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                            #endif
                            // Stop local sharing — the manager's onStop callback
                            // (wired at start) sends the "stopped" message to the
                            // peer, so manual stop and timer-expiry both notify
                            // the recipient through one path (no duplicate send).
                            if let share = myLiveShare {
                                locationManager.stopLiveSharing(sessionId: share.sessionId)
                            }
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "stop.fill")
                                    .font(.scaled(10))
                                Text("location.stop".localized)
                                    .font(.caption)
                                    .fontWeight(.medium)
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color.red)
                            .cornerRadius(15)
                        }
                        .accessibilityLabel("location.stop_sharing".localized)

                        Button {
                            showExtendOptions = true
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "plus.circle.fill")
                                    .font(.scaled(10))
                                Text("location.extend".localized)
                                    .font(.caption)
                                    .fontWeight(.medium)
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(appBrandColor)
                            .cornerRadius(15)
                        }
                        .accessibilityLabel("location.extend".localized)

                        Spacer()
                    }
                    .padding(.top, 4)
                } else if !isFromCurrentUser && locationMessage.isLive && !locationMessage.isExpired {
                    // RECEIVER: Share my location button OR stop sharing
                    HStack(spacing: 8) {
                        if !isReceiverLiveSharing && myCurrentLocation == nil {
                            // Not sharing yet - show "Share my location" button
                            Button {
                                #if !targetEnvironment(macCatalyst)
                                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                #endif
                                startReceiverLiveSharing()
                            } label: {
                                HStack(spacing: 4) {
                                    if isLoadingMyLocation {
                                        ProgressView()
                                            .scaleEffect(0.7)
                                            .tint(.white)
                                    } else {
                                        Image(systemName: "location.fill")
                                            .font(.scaled(10))
                                    }
                                    Text("location.share_mine".localized)
                                        .font(.caption)
                                        .fontWeight(.medium)
                                }
                                .foregroundColor(.white)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(appBrandColor)
                                .cornerRadius(15)
                            }
                            .disabled(isLoadingMyLocation)
                            .accessibilityLabel("location.share_mine".localized)
                        } else if isReceiverLiveSharing {
                            // 🔧 Currently live sharing - show LIVE indicator and STOP button
                            HStack(spacing: 8) {
                                HStack(spacing: 4) {
                                    Circle()
                                        .fill(appBrandColor)
                                        .frame(width: 6, height: 6)
                                        .overlay(
                                            Circle()
                                                .stroke(appBrandColor.opacity(0.4), lineWidth: 4)
                                                .scaleEffect(1.5)
                                        )
                                    Text("location.sharing_live".localized)
                                        .font(.caption)
                                        .fontWeight(.medium)
                                        .foregroundColor(appBrandColor)
                                }
                                
                                // Stop button
                                Button {
                                    #if !targetEnvironment(macCatalyst)
                                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                                    #endif
                                    stopReceiverLiveSharing()
                                } label: {
                                    HStack(spacing: 4) {
                                        Image(systemName: "stop.fill")
                                            .font(.scaled(8))
                                        Text("location.stop".localized)
                                            .font(.caption)
                                            .fontWeight(.medium)
                                    }
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(Color.red)
                                    .cornerRadius(12)
                                }
                                .accessibilityLabel("location.stop_sharing".localized)
                            }
                        } else {
                            // Shared once (one-time) - not live
                            HStack(spacing: 4) {
                                Circle()
                                    .fill(appBrandColor)
                                    .frame(width: 6, height: 6)
                                Text("location.my_location_shared".localized)
                                    .font(.caption)
                                    .foregroundColor(appBrandColor)
                            }
                        }
                        
                        Spacer()
                        
                        Button {
                            openInMaps()
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "arrow.up.right.square")
                                    .font(.scaled(10))
                                Text("location.open_in_maps".localized)
                                    .font(.caption)
                            }
                            .foregroundColor(appBrandColor)
                        }
                        .accessibilityLabel("location.open_in_maps".localized)
                    }
                    .padding(.top, 4)
                } else {
                    // Default: Open in Maps button
                    Button {
                        openInMaps()
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.up.right.square")
                                .font(.caption)
                            Text("location.open_in_maps".localized)
                                .font(.caption)
                        }
                        .foregroundColor(appBrandColor)
                    }
                    .padding(.top, 4)
                    .accessibilityLabel("location.open_in_maps".localized)
                }
            }
            .padding(10)
            .background(colorScheme == .dark ? Color(.systemGray6) : Color(.systemGray6).opacity(0.5))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .frame(width: 280)
        .onAppear {
            startTimers()
            // 🔧 FIX (2026-06-04 follow-up): start CONTINUOUS foreground location
            // updates so the receiver's own dot tracks their movement while they're
            // viewing someone else's share — not just frozen at the initial fix.
            // Gated:
            //   * receiver side only (`!isFromCurrentUser`)
            //   * skipped when the manager is already driving updates for a real
            //     live share (`isLiveSharing`) — don't fight that path
            //   * skipped if the locationUpdateTimer is already wired (sender's
            //     own bubble path), to avoid double-start
            if !isFromCurrentUser
                && locationManager.isAuthorized
                && !locationManager.isLiveSharing {
                locationManager.startForegroundLocationUpdates()
            }
        }
        .onDisappear {
            timer?.invalidate()
            locationUpdateTimer?.invalidate()
            myLiveUpdateTimer?.invalidate()  // 🔧 Also stop receiver's update timer
            // Only stop foreground updates if no active live-share owns them.
            // The live-share path manages its own start/stop in
            // startLiveSharing/stopLiveSharing.
            if !isFromCurrentUser
                && !locationManager.isLiveSharing
                && !isReceiverLiveSharing {
                locationManager.stopForegroundLocationUpdates()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("LiveLocationStopped"))) { notification in
            // 🔧 FIX: Only stop THIS bubble's timers if the sessionId matches
            let stoppedSessionId = notification.userInfo?["sessionId"] as? String

            // Check if this notification is for THIS location message's session
            let isForThisSession = (stoppedSessionId == nil) || (stoppedSessionId == locationMessage.sessionId)

            // __LIVE_STOP_COUNTDOWN_2026_09_12__ Ne PAS conditionner l'arrêt à
            // `isMyLiveShare`.
            //
            // `isMyLiveShare` dépend de `locationManager.isLiveSharing`, que
            // `stopLiveSharing()` met à faux AVANT de poster cette notification.
            // Au moment où ce bloc s'exécute, la condition est donc déjà fausse
            // pour la personne qui vient d'arrêter — et les minuteurs de SA propre
            // bulle n'étaient jamais invalidés. C'est la seconde moitié du
            // « le compte continue »: le premier minuteur continuait de rafraîchir
            // un compte à rebours que `remainingTime` calculait encore.
            //
            // On reconnaît donc la session par son identifiant, qui lui ne bouge
            // pas. Le cas `stoppedSessionId == nil` reste réservé au chemin
            // historique (`isForThisSession && isMyLiveShare`), pour qu'un arrêt
            // sans identifiant n'éteigne pas toutes les bulles de l'écran.
            let isThisExactSession = stoppedSessionId != nil
                && stoppedSessionId == locationMessage.sessionId

            if isThisExactSession || (isForThisSession && isMyLiveShare) {
                timer?.invalidate()
                locationUpdateTimer?.invalidate()
                // Sans ça le libellé reste figé sur sa dernière valeur au lieu de
                // disparaître: « 4m 12s » gelé se lit comme un partage encore actif.
                remainingTimeString = nil
                refreshTrigger.toggle()
            }

            // 🔧 FIX: Only stop receiver sharing if SENDER stopped (not when receiver stops)
            // The sender's sessionId matches the locationMessage.sessionId
            if let sessionId = stoppedSessionId,
               sessionId == locationMessage.sessionId && isReceiverLiveSharing {
                // Sender stopped their sharing, so receiver should also stop
                stopReceiverLiveSharing()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("LiveLocationExtended"))) { _ in
            updateRemainingTime()
            refreshTrigger.toggle()
        }
        // 🔧 FIX (2026-06-04): track the user's own movement on the map of
        // someone ELSE's live share. Tied to the manager's @Published
        // currentLocation so we don't need another timer. Only fires the
        // update on the receiver-side view, never on the owner's own bubble.
        .onReceive(locationManager.$currentLocation) { newLoc in
            guard !isFromCurrentUser, let loc = newLoc else { return }
            myCurrentLocation = loc.coordinate
        }
        // 🔧 Bug 5: react to the peer's back-share dict changes on the OWNER's
        // bubble. Auto-fit ONCE when Bob's back-share first becomes visible;
        // reset the latch if his entry disappears (stop / expire) so a new
        // back-share cycle re-fits cleanly. The bubble redraws via the
        // peerLiveCoordinate computed property each time this @Published emits.
        .onReceive(locationManager.$liveContactLocations) { dict in
            guard isMyLiveShare, isFromCurrentUser, let pk = contactPublicKey else {
                didFitToPeerLive = false
                return
            }
            if let entry = dict[pk], entry.expiresAt > Date() {
                if !didFitToPeerLive {
                    didFitToPeerLive = true
                    fitToOwnAnd(peerCoord: entry.coordinate)
                }
            } else {
                didFitToPeerLive = false
            }
        }
        .id(refreshTrigger) // Force view refresh when trigger changes
        .confirmationDialog("location.extend_title".localized, isPresented: $showExtendOptions, titleVisibility: .visible) {
            Button("location.extend_15min".localized) {
                if let share = myLiveShare { locationManager.extendLiveSharing(sessionId: share.sessionId, additionalMinutes: 15) }
            }
            Button("location.extend_30min".localized) {
                if let share = myLiveShare { locationManager.extendLiveSharing(sessionId: share.sessionId, additionalMinutes: 30) }
            }
            Button("location.extend_1hour".localized) {
                if let share = myLiveShare { locationManager.extendLiveSharing(sessionId: share.sessionId, additionalMinutes: 60) }
            }
            Button("common.cancel".localized, role: .cancel) { }
        }
        .fullScreenCover(isPresented: $showOshiMapFullScreen) {
            OfflineMapView(
                presentedModally: true,
                focusCoordinate: locationMessage.coordinate,
                contactName: isFromCurrentUser ? "location.me".localized : contactName,
                contactPublicKey: isFromCurrentUser ? nil : contactPublicKey,
                isLiveLocation: locationMessage.isLive && !locationMessage.isExpired,
                liveSessionId: locationMessage.sessionId,
                isOwnLiveShare: isFromCurrentUser && locationMessage.isLive && !locationMessage.isExpired
            )
        }
    }
    
    private func startTimers() {
        updateRemainingTime()

        // 🔧 FIX (2026-06-03): Make the 1s display timer fire during scroll /
        // tracking-runloop modes by adding it to .common. Without this the
        // default-mode timer pauses any time the user scrolls or interacts,
        // making the countdown appear stuck. Doesn't help after backgrounding
        // (Timers pause while suspended) but combined with the OfflineMapView
        // .onChange(of: isLiveLocation) fix and the LocationSharingManager
        // willEnterForeground observer, the on-screen countdown self-heals
        // within ~1s of return to foreground.
        let t = Timer(timeInterval: 1, repeats: true) { _ in
            updateRemainingTime()
            // 🔧 Bug 5: opportunistic expiry sweep for the peer dict — if the
            // peer's back-share quietly passed expiresAt without a stop msg
            // arriving, drop their entry. Triggers a re-render via @Published
            // so the third annotation vanishes within ~1s of expiry. Cheap:
            // single dict lookup against contactPublicKey.
            if isMyLiveShare, let pk = contactPublicKey,
               let entry = locationManager.liveContactLocations[pk],
               entry.expiresAt <= Date() {
                locationManager.clearPeerLiveLocation(peerPublicKey: pk)
            }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t

        // 🔧 FIX (2026-06-04): when looking at someone ELSE's location, seed
        // myCurrentLocation immediately so the user sees their own dot on the
        // same map without having to tap any button (WhatsApp-style). Keeps it
        // in sync via the manager's $currentLocation publisher (set up below
        // in body's .onReceive). If we don't have a fix yet, kick a one-shot
        // request — it will populate via the publisher when iOS returns it.
        if !isFromCurrentUser {
            if let currentLoc = locationManager.currentLocation {
                myCurrentLocation = currentLoc.coordinate
                fitBothLocations()
            } else if locationManager.isAuthorized {
                locationManager.getCurrentLocation { result in
                    if case .success(let loc) = result {
                        DispatchQueue.main.async {
                            myCurrentLocation = loc.coordinate
                            fitBothLocations()
                        }
                    }
                }
            }
        }

        // Real-time location update for sender's own live share (every 2 seconds for smooth map updates).
        // .common mode so it doesn't pause during scroll.
        if isMyLiveShare {
            let lut = Timer(timeInterval: 2, repeats: true) { _ in
                // __LOCATION_MAP_CAMERA_2026_08_22__ Troisième écriture dans le
                // `region` que la carte ne lisait pas: pendant son PROPRE partage
                // en direct, la vignette ne suivait donc pas le point qui bouge.
                // `followMovingPoint()` déplace la vraie caméra — et s'abstient
                // quand la carte porte deux repères, dont le cadrage est décidé
                // ailleurs.
                followMovingPoint()
            }
            RunLoop.main.add(lut, forMode: .common)
            locationUpdateTimer = lut
        }
    }
    
    private func updateRemainingTime() {
        // For sender's own live share, ALWAYS use locationManager's time (it includes extensions)
        if let share = myLiveShare {
            if share.remaining > 0 {
                let remaining = share.remaining
                formatRemainingTime(remaining)
            } else {
                remainingTimeString = nil
            }
            return
        }
        
        // For received messages or non-live, use the message's remaining time
        guard let remaining = locationMessage.remainingTime else {
            remainingTimeString = nil
            return
        }
        
        formatRemainingTime(remaining)
    }
    
    private func formatRemainingTime(_ remaining: TimeInterval) {
        let minutes = Int(remaining) / 60
        let seconds = Int(remaining) % 60
        
        if minutes >= 60 {
            let hours = minutes / 60
            let mins = minutes % 60
            remainingTimeString = String(format: "%dh %dm", hours, mins)
        } else if minutes > 0 {
            remainingTimeString = String(format: "%dm %ds", minutes, seconds)
        } else {
            remainingTimeString = String(format: "%ds", seconds)
        }
    }
    
    private func shareMyLocation() {
        isLoadingMyLocation = true
        
        locationManager.getCurrentLocation { result in
            DispatchQueue.main.async {
                isLoadingMyLocation = false
                
                switch result {
                case .success(let location):
                    myCurrentLocation = location.coordinate
                    
                    // Also send as a message via notification
                    if let onSend = onSendLocation {
                        let myLocationMsg = LocationMessage.oneTime(from: location, address: nil)
                        onSend(myLocationMsg)
                    } else {
                        // Use notification to send location
                        let myLocationMsg = LocationMessage.oneTime(from: location, address: nil)
                        NotificationCenter.default.post(
                            name: NSNotification.Name("SendLocationFromBubble"),
                            object: nil,
                            userInfo: ["locationMessage": myLocationMsg]
                        )
                    }
                    
                    // Fit both locations in view
                    fitBothLocations()
                    
                case .failure(let error):
                    OshiLog.maps.info("❌ Failed to get my location: \(error)")
                }
            }
        }
    }
    
    // 🔧 FIX: Start receiver live sharing with EXACT same remaining time as sender
    private func startReceiverLiveSharing() {
        isLoadingMyLocation = true

        // 🔧 FIX: Use the EXACT remaining time from sender's location message
        // This ensures both expire at the same time (WhatsApp-style)
        let remainingSeconds: TimeInterval
        if let remaining = locationMessage.remainingTime {
            remainingSeconds = remaining
        } else {
            // Fallback to 15 minutes if no remaining time available
            remainingSeconds = 15 * 60
        }

        // Generate unique session ID for receiver's sharing
        let sessionId = UUID().uuidString
        receiverLiveSessionId = sessionId

        locationManager.getCurrentLocation { result in
            DispatchQueue.main.async {
                self.isLoadingMyLocation = false

                switch result {
                case .success(let location):
                    self.myCurrentLocation = location.coordinate
                    self.isReceiverLiveSharing = true

                    // 🔧 FIX: Send initial live location message with EXACT remaining duration
                    let initialMsg = LocationMessage.liveWithCustomDuration(
                        from: location,
                        durationSeconds: remainingSeconds,
                        address: nil,
                        sessionId: sessionId
                    )

                    if let onSend = self.onSendLocation {
                        onSend(initialMsg)
                    } else {
                        NotificationCenter.default.post(
                            name: NSNotification.Name("SendLocationFromBubble"),
                            object: nil,
                            userInfo: ["locationMessage": initialMsg]
                        )
                    }

                    // Start update timer (every 10 seconds).
                    // 🔧 FIX (2026-06-04): capture absolute `expiresAt` ONCE
                    // when the receiver starts back-sharing, and reuse it on
                    // every update via liveUpdateWithExpiry. The old code
                    // passed `durationSeconds` and rebuilt `now + duration`
                    // on every tick, which made the receiver's bubble keep
                    // pushing the expiry forward — share never ended.
                    // Note: struct can't use [weak self]; capture values.
                    self.myLiveUpdateTimer?.invalidate()
                    let capturedOnSend = self.onSendLocation
                    let capturedExpiresAt = Date().addingTimeInterval(remainingSeconds)
                    let capturedSessionId = sessionId

                    // .common runloop mode so the timer doesn't pause while
                    // the user scrolls the chat or the runloop is being driven
                    // by CLLocationManager background callbacks.
                    let t = Timer(timeInterval: 10, repeats: true) { timer in
                        // Check if still sharing (via state)
                        guard self.isReceiverLiveSharing else {
                            timer.invalidate()
                            return
                        }

                        LocationSharingManager.shared.getCurrentLocation { result in
                            if case .success(let updatedLocation) = result {
                                DispatchQueue.main.async {
                                    let updateMsg = LocationMessage.liveUpdateWithExpiry(
                                        from: updatedLocation,
                                        expiresAt: capturedExpiresAt,
                                        sessionId: capturedSessionId
                                    )

                                    if let onSend = capturedOnSend {
                                        onSend(updateMsg)
                                    } else {
                                        NotificationCenter.default.post(
                                            name: NSNotification.Name("SendLocationFromBubble"),
                                            object: nil,
                                            userInfo: ["locationMessage": updateMsg]
                                        )
                                    }
                                }
                            }
                        }
                    }
                    RunLoop.main.add(t, forMode: .common)
                    self.myLiveUpdateTimer = t

                    // Auto-stop when sender's location expires (same time as sender)
                    DispatchQueue.main.asyncAfter(deadline: .now() + remainingSeconds) {
                        if self.isReceiverLiveSharing && self.receiverLiveSessionId == sessionId {
                            self.stopReceiverLiveSharing()
                        }
                    }

                    self.fitBothLocations()
                    let minutes = Int(remainingSeconds / 60)
                    OshiLog.maps.info("📍 Receiver started live sharing for \(minutes) min (sessionId: \(sessionId.prefix(8))...)")

                case .failure(let error):
                    OshiLog.maps.info("❌ Failed to start receiver live sharing: \(error)")
                }
            }
        }
    }
    
    // 🔧 NEW: Stop receiver live sharing
    private func stopReceiverLiveSharing() {
        guard isReceiverLiveSharing, let sessionId = receiverLiveSessionId else { return }
        
        OshiLog.maps.info("📍 Receiver stopping live sharing (sessionId: \(sessionId.prefix(8))...)")
        
        // Stop update timer
        myLiveUpdateTimer?.invalidate()
        myLiveUpdateTimer = nil
        
        // Send stopped message
        let stoppedMsg = LocationMessage.stopped(sessionId: sessionId, lastLocation: locationManager.currentLocation)
        
        if let onSend = onSendLocation {
            onSend(stoppedMsg)
        } else {
            NotificationCenter.default.post(
                name: NSNotification.Name("SendLocationFromBubble"),
                object: nil,
                userInfo: ["locationMessage": stoppedMsg]
            )
        }
        
        // Reset state
        isReceiverLiveSharing = false
        receiverLiveSessionId = nil
        // Keep myCurrentLocation to still show position on map (but not updating)
        
        OshiLog.maps.info("📍 Receiver live sharing stopped")
    }
    
    private func updateMyLocation() {
        guard myCurrentLocation != nil else { return }
        
        locationManager.getCurrentLocation { result in
            if case .success(let location) = result {
                DispatchQueue.main.async {
                    myCurrentLocation = location.coordinate
                }
            }
        }
    }
    
    private func fitBothLocations() {
        guard let myLoc = myCurrentLocation else { return }
        
        let theirLoc = locationMessage.coordinate
        
        // Calculate center and span to fit both
        let minLat = min(myLoc.latitude, theirLoc.latitude)
        let maxLat = max(myLoc.latitude, theirLoc.latitude)
        let minLon = min(myLoc.longitude, theirLoc.longitude)
        let maxLon = max(myLoc.longitude, theirLoc.longitude)
        
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        
        let span = MKCoordinateSpan(
            latitudeDelta: max(0.01, (maxLat - minLat) * 1.5),
            longitudeDelta: max(0.01, (maxLon - minLon) * 1.5)
        )
        
        withAnimation {
            camera = .region(MKCoordinateRegion(center: center, span: span))
        }
    }
    
    /// 🔧 Bug 5: pan/zoom to fit Alice's own moving location + Bob's back-share
    /// coord on Alice's bubble. Called ONCE per back-share appearance via the
    /// didFitToPeerLive latch — not on every coord update — to avoid jitter.
    private func fitToOwnAnd(peerCoord: CLLocationCoordinate2D) {
        let mine = effectiveCoordinate
        let minLat = min(mine.latitude, peerCoord.latitude)
        let maxLat = max(mine.latitude, peerCoord.latitude)
        let minLon = min(mine.longitude, peerCoord.longitude)
        let maxLon = max(mine.longitude, peerCoord.longitude)
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max(0.01, (maxLat - minLat) * 1.5),
            longitudeDelta: max(0.01, (maxLon - minLon) * 1.5)
        )
        withAnimation {
            camera = .region(MKCoordinateRegion(center: center, span: span))
        }
    }

    /// Recentre sur le point suivi quand il n'y a qu'un point à montrer. Dès que
    /// la carte porte DEUX repères (le mien + le sien, ou mon partage + son
    /// retour), le cadrage est décidé par `fitBothLocations` / `fitToOwnAnd` et
    /// recentrer ici les défierait à chaque mise à jour.
    private func followMovingPoint() {
        guard myCurrentLocation == nil, peerLiveCoordinate == nil else { return }
        let target = effectiveCoordinate
        withAnimation(.easeInOut(duration: 0.35)) {
            camera = .region(MKCoordinateRegion(
                center: target,
                latitudinalMeters: LocationMessageBubble.spanMeters,
                longitudinalMeters: LocationMessageBubble.spanMeters
            ))
        }
    }

    private func openInMaps() {
        // Settings › Maps — see MapOpener. OSHI keeps its richer presentation
        // (contact avatar, live tracking) via the fullScreenCover below.
        MapOpener.open(
            coordinate: locationMessage.coordinate,
            name: locationMessage.address ?? "location.shared_location".localized
        ) {
            showOshiMapFullScreen = true
        }
    }
}

/// Transparent carrier for `MapOpener.presentOshiMap`: shows OSHI's map in a
/// real SwiftUI fullScreenCover, and removes itself when the map is closed.
private struct OshiMapFocusHost: View {
    let focus: CLLocationCoordinate2D
    @State private var showMap = false
    @Environment(\.dismiss) private var dismissHost

    var body: some View {
        Color.clear
            .ignoresSafeArea()
            .onAppear { showMap = true }
            .fullScreenCover(isPresented: $showMap, onDismiss: {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) { dismissHost() }
            }) {
                OfflineMapView(presentedModally: true, focusCoordinate: focus)
            }
    }
}

// MARK: - Live Location Banner

struct LiveLocationBanner: View {
    @ObservedObject var locationManager = LocationSharingManager.shared
    /// The conversation whose share this banner shows. nil = the most recent
    /// share, whichever conversation it belongs to.
    var conversationId: String? = nil

    private var share: LocationSharingManager.ActiveLiveShare? {
        if let conversationId { return locationManager.liveShare(for: conversationId) }
        return locationManager.activeLiveShares.last
    }

    @State private var remainingTimeString: String = ""
    @State private var timer: Timer?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let share {
            HStack(spacing: 12) {
                // Pulsing indicator (respect Reduce Motion)
                Circle()
                    .fill(appBrandColor)
                    .frame(width: 10, height: 10)
                    .overlay(
                        Circle()
                            .stroke(appBrandColor.opacity(0.5), lineWidth: 2)
                            .scaleEffect(reduceMotion ? 1.0 : 1.5)
                            .opacity(0.5)
                    )
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text("location.sharing_live".localized)
                        .font(.caption)
                        .fontWeight(.medium)
                    Text("\("location.expires_in".localized) \(remainingTimeString)")
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
                .accessibilityElement(children: .combine)

                Spacer()

                Button {
                    #if !targetEnvironment(macCatalyst)
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    #endif
                    locationManager.stopLiveSharing(sessionId: share.sessionId)
                } label: {
                    Text("location.stop_sharing".localized)
                        .font(.caption)
                        .fontWeight(.medium)
                        .foregroundColor(.red)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(Color.red.opacity(0.1))
                        .clipShape(Capsule())
                }
                .accessibilityLabel("location.stop_sharing".localized)
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
            .frame(maxWidth: locationContentMaxWidth)
            .frame(maxWidth: .infinity)
            .background(appBrandColor.opacity(0.1))
            .onAppear {
                startTimer()
            }
            .onDisappear {
                timer?.invalidate()
            }
            .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("LiveLocationExtended"))) { _ in
                updateRemainingTime()
            }
        }
    }
    
    private func startTimer() {
        updateRemainingTime()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in
            updateRemainingTime()
        }
    }
    
    private func updateRemainingTime() {
        guard let remaining = share?.remaining, remaining > 0 else {
            remainingTimeString = "0:00"
            return
        }
        
        let minutes = Int(remaining) / 60
        let seconds = Int(remaining) % 60
        remainingTimeString = String(format: "%d:%02d", minutes, seconds)
    }
}
