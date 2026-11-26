//
//  MeshtasticSettingsView.swift
//  OSHI — pair & manage a long-range LoRa radio (Meshtastic) (M1)
//

import SwiftUI
import CoreBluetooth
import CoreLocation

struct MeshtasticSettingsView: View {
    @ObservedObject private var mesh = MeshtasticManager.shared
    @ObservedObject private var meshCore = MeshCoreManager.shared
    @AppStorage(LoRaCrossBridge.enabledKey) private var crossBridge = true
    @State private var showForgetMeshCore = false
    @State private var showJoinMeshCoreChannel = false
    /// __LORA_MANUAL_LINK_2026_10_28__ The node awaiting a contact, and a
    /// counter that forces the rows to redraw after a link changes — the map is
    /// a plain object, not an ObservableObject, so nothing here observes it.
    /// A node number is the identity here, but `UInt32` is not `Identifiable`
    /// and making it so retroactively would put that conformance on every
    /// integer in the target. One wrapper instead.
    private struct LinkTarget: Identifiable { let id: UInt32 }
    @State private var linkingNode: LinkTarget?
    @State private var linkRevision = 0
    @State private var testText = ""
    @State private var showForgetConfirm = false
    // Opt-in, off by default. Read outside SwiftUI by MeshtasticManager via the
    // same UserDefaults key, so the key string here is the contract — do not
    // rename one without the other. [Media-over-LoRa Phase 0 2026-09-11]
    @AppStorage(MeshtasticManager.mediaOverLoRaKey) private var mediaOverLoRa = false
    @AppStorage(MeshtasticManager.positionBeaconKey) private var positionBeacon = false

    var body: some View {
        List {
            // __MESHCORE_INTEROP_2026_11_25__ The radio in use comes first: with only a MeshCore radio, the
            // screen used to open on "No radio paired" (the Meshtastic card) above a connected MeshCore radio.
            if meshCoreFirst { meshCoreSection }

            // MARK: Status / paired radio
            Section {
                HStack(spacing: 14) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .font(.scaled(28))
                        .foregroundColor(mesh.isReady ? .green : .secondary)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(mesh.displayName ?? "meshtastic.no_radio".localized)
                            .font(.headline)
                        Text(statusLine)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    if mesh.state == .connecting || mesh.state == .configuring {
                        ProgressView()
                    }
                }
                .padding(.vertical, 4)

                if mesh.connectedName != nil {
                    Button(role: .destructive) {
                        showForgetConfirm = true
                    } label: {
                        Label("meshtastic.forget".localized, systemImage: "xmark.circle")
                    }
                    .confirmationDialog("meshtastic.forget_confirm".localized,
                                        isPresented: $showForgetConfirm, titleVisibility: .visible) {
                        Button("meshtastic.forget".localized, role: .destructive) {
                            mesh.disconnect(forget: true)
                        }
                    }
                }
            } footer: {
                Text("meshtastic.footer".localized)
            }

            // MARK: Detection — any compatible radio nearby appears here
            if mesh.connectedName == nil {
                Section("meshtastic.nearby".localized) {
                    if mesh.permissionDenied {
                        // Permission denial is NOT "Bluetooth off" — offer the way out.
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) {
                                UIApplication.shared.open(url)
                            }
                        } label: {
                            Label("meshtastic.bt_denied".localized, systemImage: "gear")
                                .foregroundColor(.orange)
                        }
                    } else if !mesh.bluetoothOn {
                        Label("meshtastic.bt_off".localized, systemImage: "exclamationmark.triangle")
                            .foregroundColor(.orange)
                    } else {
                        Button {
                            mesh.state == .scanning ? mesh.stopScan() : mesh.startScan()
                        } label: {
                            Label(mesh.state == .scanning ? "meshtastic.stop_scan".localized
                                                          : "meshtastic.scan".localized,
                                  systemImage: mesh.state == .scanning ? "stop.circle" : "dot.radiowaves.left.and.right")
                        }
                        if mesh.state == .scanning && mesh.discovered.isEmpty {
                            HStack { ProgressView(); Text("meshtastic.searching".localized).foregroundColor(.secondary) }
                        }
                        ForEach(mesh.discovered, id: \.peripheral.identifier) { item in
                            Button { mesh.connect(item.peripheral) } label: {
                                HStack {
                                    Image(systemName: "flipphone")
                                    VStack(alignment: .leading) {
                                        Text(item.peripheral.name ?? "LoRa radio")
                                        Text("RSSI \(item.rssi) dBm").font(.caption2).foregroundColor(.secondary)
                                    }
                                    Spacer()
                                    Text("meshtastic.pair".localized).foregroundColor(.accentColor)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }

            // MARK: Stock Meshtastic radio -> offer OSHI Mesh (keeps the node's settings)
            if mesh.isReady && mesh.firmwareProbeDone && mesh.firmwareFamily == .meshtastic {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Label("oshimesh.upgrade.title".localized, systemImage: "arrow.up.circle.fill")
                            .font(.headline)
                        Text("oshimesh.upgrade.body".localized)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .padding(.vertical, 4)
                    if let url = URL(string: "https://oshi-messenger.com/lora/flash/") {
                        ShareLink(item: url) {
                            Label("oshimesh.upgrade.share".localized, systemImage: "square.and.arrow.up")
                        }
                        Link(destination: URL(string: "https://oshi-messenger.com/lora/why-oshi-mesh")!) {
                            Label("oshimesh.upgrade.why".localized, systemImage: "info.circle")
                        }
                    }
                } footer: {
                    Text("oshimesh.upgrade.footer".localized)
                }
            }

            if !meshCoreFirst { meshCoreSection }

            // MARK: Mesh nodes seen by the radio
            if mesh.isReady {
                Section("meshtastic.nodes".localized + " (\(mesh.nodes.count))") {
                    if mesh.nodes.isEmpty {
                        Text("meshtastic.no_nodes".localized).foregroundColor(.secondary)
                    }
                    ForEach(mesh.nodes) { node in
                        HStack {
                            Text(node.shortName.isEmpty ? "•" : node.shortName)
                                .font(.caption).bold()
                                .frame(width: 44, height: 30)
                                .background(Color.accentColor.opacity(0.15))
                                .clipShape(RoundedRectangle(cornerRadius: 7))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(node.longName.isEmpty ? node.userId : node.longName)
                                Text(nodeDetail(node))
                                    .font(.caption2).foregroundColor(.secondary)
                                // __LORA_MANUAL_LINK_2026_10_28__ Which contact's
                                // conversation this radio's stock text lands in,
                                // when a person has said. Shown on the row rather
                                // than behind the menu: the whole point of the link
                                // is that the thread is no longer where you would
                                // expect it, so the row has to say where it went.
                                if let linked = LoRaNodeIdentityMap.shared.manualPublicKey(forNode: node.num) {
                                    Text(MessageManager.resolveContactAlias(linked))
                                        .font(.caption2)
                                        .foregroundColor(.accentColor)
                                }
                            }
                        }
                        // Long-press, not a tap: tapping a row in this list has
                        // never done anything, and making it open a picker would
                        // fire on every mis-touch while reading signal numbers.
                        .contextMenu {
                            Button {
                                linkingNode = LinkTarget(id: node.num)
                            } label: {
                                Label("contact_selector.title".localized,
                                      systemImage: "person.crop.circle.badge.plus")
                            }
                            if LoRaNodeIdentityMap.shared.manualPublicKey(forNode: node.num) != nil {
                                Button(role: .destructive) {
                                    LoRaNodeIdentityMap.shared.unlink(node: node.num)
                                    linkRevision += 1
                                } label: {
                                    Label("remove".localized, systemImage: "person.crop.circle.badge.minus")
                                }
                            }
                        }
                    }
                    .id(linkRevision)
                }

                // MARK: Test message (interop broadcast — official app users see it)
                Section {
                    HStack {
                        TextField("meshtastic.test_placeholder".localized, text: $testText)
                        Button {
                            let t = testText.isEmpty ? "Hello from OSHI 👋" : testText
                            mesh.sendText(t)
                            testText = ""
                        } label: { Image(systemName: "paperplane.fill") }
                        .disabled(!mesh.isReady)
                    }
                } header: {
                    Text("meshtastic.test".localized)
                } footer: {
                    // The default channel is public — say so (E2E only applies to OSHI↔OSHI).
                    Text("meshtastic.test_warning".localized)
                }
            }

            // MARK: Why the last message did not go out
            //
            // Sits ABOVE the traffic log on purpose: it is the answer to the
            // question that brings someone to this screen. Four refusal paths
            // used to end in an OshiLog line and nothing else, so a message just
            // failed and the reason — usually a ten-second fix — was invisible.
            // __ANTI_JAMMING_PHASE1_2026_11_13__ Above the refusal: when the
            // channel looks jammed, that is the answer to "why is nothing moving".
            LoRaJammingWarningSection()

            if let refusal = mesh.lastRefusal {
                Section {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.orange)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(refusal.title)
                                .font(.subheadline).bold()
                            Text(refusal.detail)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                            if let at = mesh.lastRefusalAt {
                                Text(at, style: .time)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                    .padding(.vertical, 2)
                    .accessibilityElement(children: .combine)

                    // The two refusals the user can fix right here get a way in,
                    // rather than a description of a screen they have to find.
                    if refusal == .noRegion || refusal == .radioMuted {
                        NavigationLink {
                            LoRaNodeSetupView()
                        } label: {
                            Label("lora.blocked.fix".localized, systemImage: "wrench.and.screwdriver")
                        }
                    }
                } header: {
                    Text("lora.blocked.section".localized)
                }
            }

            // MARK: Media over LoRa (opt-in, last resort, off by default)
            //
            // LoRa carries text well and media terribly: a photo re-encoded to
            // fit the ~12-frame ceiling still takes minutes on the air, and only
            // the smallest images fit at all. So this stays OFF unless the person
            // deliberately turns it on for a true no-internet situation. When it
            // is off, the existing `.carriesMedia` refusal above is unchanged.
            Section {
                Toggle("lora.media.toggle".localized, isOn: $mediaOverLoRa)
                    .disabled(mesh.connectedName == nil)
            } header: {
                Text("lora.media.section".localized)
            } footer: {
                Text("lora.media.footer".localized)
            }

            // MARK: Beacon my position (opt-in, off by default)
            //
            // This switch is what makes `broadcastPosition` reachable at all: before it,
            // both phones could encode a Position frame and neither ever sent one.
            //
            // The footer has to say what this EXPOSES, not just what it does. The frame
            // leaves in cleartext to the broadcast address on the default LongFast
            // channel, whose key is published in Meshtastic's own source, so every radio
            // in range hears it — tens of kilometres, people the person has never met and
            // cannot list. A switch labelled only "share my position" would be describing
            // a private feature.
            Section {
                Toggle("lora.position.toggle".localized, isOn: $positionBeacon)
                    .disabled(mesh.connectedName == nil)
                    .onChange(of: positionBeacon) { _, on in
                        // Beacon AT ONCE instead of up to a tick later, so flipping the
                        // switch while watching another phone's map does something.
                        if on { LoRaPositionBeacon.shared.switchTurnedOn() }
                    }
            } header: {
                Text("lora.position.section".localized)
            } footer: {
                Text("lora.position.footer".localized)
            }

            // MARK: Live traffic
            if !mesh.traffic.isEmpty {
                Section("meshtastic.traffic".localized) {
                    ForEach(mesh.traffic.prefix(12)) { e in
                        HStack(alignment: .top, spacing: 8) {
                            Text(e.direction).bold()
                                .foregroundColor(e.direction == "→" ? .blue : .green)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(e.summary).font(.caption)
                                Text(e.date, style: .time).font(.caption2).foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }

            if let err = mesh.lastError {
                Section { Text(err).font(.caption).foregroundColor(.red) }
            }
        }
        .navigationTitle("meshtastic.title".localized)
        // Both families are looked for at once: a MeshCore radio is found by its own service, so the person
        // never has to know which firmware their radio runs to pick the right list.
        .onAppear {
            if mesh.connectedName == nil { mesh.startScan() }
            if !MeshCoreManager.isPaired { meshCore.startScan() }
        }
        .onDisappear { mesh.stopScan(); meshCore.stopScan() }
        // __LORA_MANUAL_LINK_2026_10_28__ Reuses the app's own contact picker
        // rather than a second list of contacts: it already searches, already
        // resolves shared nicknames over local aliases, and is already
        // translated. `item:` and not `isPresented:` so the node travels with
        // the sheet — with a bool the picker could outlive the row it was
        // opened from and link the wrong radio.
        .sheet(item: $linkingNode) { target in
            ContactSelectorView { publicKey, _ in
                LoRaNodeIdentityMap.shared.link(node: target.id, toIdentity: publicKey)
                linkRevision += 1
                linkingNode = nil
            }
        }
    }

    // MARK: MeshCore radio — OSHI over MeshCore repeaters, and this phone as a bridge between the two networks
    @ViewBuilder private var meshCoreSection: some View {
        Section {
            HStack(spacing: 14) {
                Image(systemName: "point.3.connected.trianglepath.dotted")
                    .font(.scaled(24))
                    .foregroundColor(meshCore.isTextReady ? .green : .secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(meshCore.connectedName ?? "meshcore.none".localized).font(.headline)
                    if let line = meshCoreStatusLine {
                        Text(line).font(.caption)
                            .foregroundColor(MeshCoreError.needsAction(meshCore.lastError) && meshCore.state == .off
                                             ? .orange : .secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    if meshCore.isTextReady, let name = meshCore.selfName {
                        Text("meshcore.seenAs".localized(with: name)).font(.caption2).foregroundColor(.secondary)
                    }
                }
                Spacer()
                if meshCore.state == .connecting || meshCore.state == .configuring || meshCore.awaitingPin { ProgressView() }
            }
            if meshCore.awaitingPin {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Image(systemName: "key.fill").foregroundColor(.blue).accessibilityHidden(true)
                    Text("meshcore.state.pin.detail".localized)
                        .foregroundColor(.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .font(.caption)
            }
            if meshCore.lastError == MeshCoreError.noFreeChannel {
                Label("meshcore.noFreeChannel".localized, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundColor(.orange)
            }
            if MeshCoreError.needsAction(meshCore.lastError) && MeshCoreManager.isPaired && meshCore.state == .off {
                Button { meshCore.retryConnection() } label: {
                    Label("meshcore.retry".localized, systemImage: "arrow.clockwise")
                }
            }
            if meshCore.connectedName != nil || MeshCoreManager.isPaired {
                Button(role: .destructive) { showForgetMeshCore = true } label: {
                    Label("meshcore.forget".localized, systemImage: "xmark.circle")
                }
                .confirmationDialog("meshcore.forget".localized, isPresented: $showForgetMeshCore, titleVisibility: .visible) {
                    Button("meshcore.forget".localized, role: .destructive) { meshCore.forget() }
                }
            } else if mesh.bluetoothOn {
                Button {
                    meshCore.state == .scanning ? meshCore.stopScan() : meshCore.startScan()
                } label: {
                    Label(meshCore.state == .scanning ? "meshtastic.stop_scan".localized : "meshcore.scan".localized,
                          systemImage: meshCore.state == .scanning ? "stop.circle" : "dot.radiowaves.left.and.right")
                }
                if meshCore.state == .scanning && meshCore.discovered.isEmpty {
                    HStack { ProgressView(); Text("meshtastic.searching".localized).foregroundColor(.secondary) }
                }
                ForEach(meshCore.discovered, id: \.identifier) { p in
                    Button { meshCore.connect(p) } label: {
                        HStack {
                            Image(systemName: "flipphone")
                            VStack(alignment: .leading) {
                                Text(p.name ?? "MeshCore")
                                Text(meshCoreFoundCaption(p.identifier)).font(.caption2).foregroundColor(.secondary)
                            }
                            Spacer()
                            Text("meshtastic.pair".localized).foregroundColor(.accentColor)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        } header: {
            Text("meshcore.title".localized)
        } footer: {
            Text("meshcore.footer".localized)
        }

        if meshCore.isReady && mesh.isReady {
            Section {
                Toggle("meshcore.bridge".localized, isOn: $crossBridge)
            } footer: {
                Text("meshcore.bridge.footer".localized)
            }
        }

        if meshCore.isTextReady {
            meshCoreChannelsSection
            meshCoreContactsSection
        }
    }

    // MARK: MeshCore users (__MESHCORE_INTEROP_2026_11_25__)

    private func openMeshCore(_ key: String, title: String) {
        MessageManager.shared?.openMeshCoreThread(key: key, title: title)
    }

    @ViewBuilder private var meshCoreChannelsSection: some View {
        Section {
            ForEach(meshCore.userChannels, id: \.idx) { ch in
                Button {
                    if let key = MeshCoreInterop.channelKey(name: ch.name) { openMeshCore(key, title: ch.name) }
                } label: {
                    HStack {
                        Image(systemName: "number").foregroundColor(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ch.name)
                            Text((MeshCoreInterop.isPubliclyReadable(name: ch.name, secret: ch.secret)
                                  ? "meshcore.interop.channel.public" : "meshcore.interop.channel.private").localized)
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary.opacity(0.6))
                    }
                }
                .buttonStyle(.plain)
                .swipeActions {
                    if ch.idx > 0 {
                        Button(role: .destructive) { meshCore.leaveChannel(idx: ch.idx) } label: {
                            Text("meshcore.channel.leave".localized)
                        }
                    }
                }
            }
            if meshCore.lastError == MeshCoreError.channelFailed {
                Label("meshcore.channel.failed".localized, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundColor(.orange)
            }
            Button { showJoinMeshCoreChannel = true } label: {
                Label("meshcore.channel.join".localized, systemImage: "plus.circle")
            }
        } header: {
            Text("meshcore.channels".localized)
        } footer: {
            Text("meshcore.channels.footer".localized)
        }
        .sheet(isPresented: $showJoinMeshCoreChannel) {
            MeshCoreJoinChannelSheet()
        }
        // Joining is for talking: once the radio accepts the channel, its thread opens (as on Android).
        .onChange(of: meshCore.lastJoinedChannelKey) { _, key in
            if let key, let name = MeshCoreInterop.channelName(fromKey: key) { openMeshCore(key, title: name) }
        }
    }

    @ViewBuilder private var meshCoreContactsSection: some View {
        Section {
            // People only: repeaters, room servers and sensors are infrastructure, not someone to write to.
            let people = meshCore.contacts.filter { $0.type == 1 }
            if people.isEmpty {
                Text("meshcore.contacts.none".localized).font(.caption).foregroundColor(.secondary)
            }
            ForEach(people.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending },
                    id: \.hex) { c in
                Button {
                    openMeshCore(MeshCoreInterop.contactKey(publicKey: c.publicKey),
                                 title: c.name.isEmpty ? String(c.hex.prefix(8)) : c.name)
                } label: {
                    HStack {
                        Image(systemName: "person.crop.circle")
                            .foregroundColor(.secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.name.isEmpty ? String(c.hex.prefix(8)) : c.name)
                            Text(meshCoreHeard(c))
                                .font(.caption2).foregroundColor(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.caption2).foregroundColor(.secondary.opacity(0.6))
                    }
                }
                .buttonStyle(.plain)
            }
            Button { meshCore.refreshContacts() } label: {
                Label("meshcore.contacts.refresh".localized, systemImage: "arrow.clockwise")
            }
            Button { meshCore.announce() } label: {
                Label("meshcore.announce".localized, systemImage: "megaphone")
            }
            if meshCore.lastAdvertAt != nil {
                Label("meshcore.announce.done".localized, systemImage: "checkmark.circle")
                    .font(.caption).foregroundColor(.green)
            }
        } header: {
            Text("meshcore.contacts".localized)
        } footer: {
            Text("meshcore.contacts.footer".localized)
        }
    }

    /// "2 min ago · 26aa9155" — when the contact last announced itself (the radio's clock), and its key.
    private func meshCoreHeard(_ c: MeshCore.Contact) -> String {
        let key = String(c.hex.prefix(8))
        guard c.lastAdvert > 1_600_000_000 else { return key }
        let when = Date(timeIntervalSince1970: TimeInterval(c.lastAdvert))
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: min(when, Date()), relativeTo: Date()) + " · " + key
    }

    private var meshCoreFirst: Bool {
        mesh.connectedName == nil && (meshCore.connectedName != nil || MeshCoreManager.isPaired)
    }

    private var meshCoreStatusLine: String? {
        MeshCoreUI.statusKey(state: meshCore.state, awaitingPin: meshCore.awaitingPin, lastError: meshCore.lastError,
                             isPaired: MeshCoreManager.isPaired, bridgeReady: meshCore.isReady)?.localized
    }

    /// "MeshCore firmware · −67 dBm": the scan only finds MeshCore companion radios, so the firmware is known.
    private func meshCoreFoundCaption(_ id: UUID) -> String {
        guard let rssi = meshCore.discoveredRSSI[id] else { return "meshcore.found".localized }
        return "meshcore.found".localized + " · \(rssi) dBm"
    }

    private var statusLine: String {
        switch mesh.state {
        case .off:         return "meshtastic.state.off".localized
        case .scanning:    return "meshtastic.state.scanning".localized
        case .connecting:  return "meshtastic.state.connecting".localized
        case .configuring: return "meshtastic.state.configuring".localized
        case .ready:
            let id = String(format: "!%08x", mesh.myNodeNum)
            return "meshtastic.state.ready".localized + " — \(id)"
        }
    }

    /// Signal AND distance, because dB alone means nothing to most people.
    ///
    /// Distance is only shown when it is genuinely known: the node has to have
    /// broadcast a position (bare boards like a Heltec V3 have no GPS and never
    /// will) and this phone has to have a fix. Showing "0 km" for a node that
    /// simply never said where it is would be a made-up number.
    private func nodeDetail(_ node: MeshtasticNode) -> String {
        var parts: [String] = []

        if let km = distanceKm(to: node) {
            // Both ends know where they are: a real distance, no qualifier.
            // __DISTANCE_FOLLOWS_THE_LOCALE_2026_10_21__ m/km or ft/mi.
            parts.append(MeshDistance.format(km: km))
        } else if let r = node.estimatedRangeKm {
            // No GPS anywhere in the picture — fall back to signal strength.
            // Shown as a band with a "~", because it IS a band: the same RSSI
            // means very different distances over water and through a town.
            parts.append("~" + span(r.min, r.max))
        }

        if let rssi = node.rssi { parts.append("\(rssi) dBm") }
        parts.append(String(format: "SNR %.1f dB", node.snr))

        if let hops = node.hopsAway {
            parts.append(hops == 0
                         ? "meshtastic.direct".localized
                         : String(format: "meshtastic.hops".localized, hops))
        }
        return parts.joined(separator: " · ")
    }

    /// "300–900 m", "1.2–4.8 km", "0.8–3.0 mi" — the unit comes from the
    /// reader's locale, and from the far end so the two halves never disagree.
    private func span(_ lo: Double, _ hi: Double) -> String {
        MeshDistance.span(minKm: lo, maxKm: hi)
    }

    private func distanceKm(to node: MeshtasticNode) -> Double? {
        guard let lat = node.latitude, let lon = node.longitude,
              let here = LocationSharingManager.shared.currentLocation else { return nil }
        let there = CLLocation(latitude: lat, longitude: lon)
        return here.distance(from: there) / 1000.0
    }
}

// MARK: - Jamming warning (__ANTI_JAMMING_PHASE1_2026_11_13__)

/// Shown on both LoRa screens (`LoRaNodeSetupView`, `MeshtasticSettingsView`)
/// while a radio is connected. Non-blocking: the app already routes around the
/// radio, and saying so is the point. Carries the one action the detector can be
/// overruled with, "Send over LoRa anyway" (`MeshtasticManager.overrideJammingExclusion`),
/// for 10 minutes or until the channel is clear.
struct LoRaJammingWarningSection: View {
    @ObservedObject private var mesh = MeshtasticManager.shared

    var body: some View {
        if mesh.isReady && mesh.jamming == .probableJamming {
            Section {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.orange)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("lora.jamming.title".localized)
                            .font(.subheadline).bold()
                        Text("lora.jamming.detail".localized)
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.vertical, 2)
                .accessibilityElement(children: .combine)

                if let until = mesh.jammingOverrideUntil {
                    Text(String(format: "lora.jamming.override.active".localized,
                                until.formatted(date: .omitted, time: .shortened)))
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                    Button {
                        mesh.cancelJammingOverride()
                    } label: {
                        Label("lora.jamming.override.cancel".localized, systemImage: "xmark.circle")
                    }
                } else {
                    Button {
                        mesh.overrideJammingExclusion()
                    } label: {
                        Label("lora.jamming.override".localized,
                              systemImage: "antenna.radiowaves.left.and.right")
                    }
                    .accessibilityIdentifier("lora.jamming.override")
                }
            } footer: {
                if mesh.jammingOverrideUntil == nil {
                    Text("lora.jamming.override.footer".localized)
                }
            }
        } else if mesh.isReady && mesh.jamming == .congested {
            Section {
                Label("lora.congested".localized, systemImage: "antenna.radiowaves.left.and.right")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }
}

/// __MESHCORE_INTEROP_2026_11_25__ Join a MeshCore channel: a hashtag one (key derived from the name, so
/// readable by anyone who knows it) or a private one (name + the 16-byte secret its owner shared).
private struct MeshCoreJoinChannelSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var meshCore = MeshCoreManager.shared
    @State private var isPrivate = false
    @State private var name = ""
    @State private var secret = ""
    @State private var failed = false

    private var parsed: (name: String, secret: Data)? {
        if isPrivate {
            let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !n.isEmpty, n.utf8.count <= 31, let s = MeshCoreInterop.parseSecret(secret),
                  MeshCoreInterop.channelKey(name: n) != nil else { return nil }
            return (n, s)
        }
        guard let n = MeshCoreInterop.normalizedHashtag(name), MeshCoreInterop.channelKey(name: n) != nil else { return nil }
        return (n, MeshCoreInterop.hashtagSecret(n))
    }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    Picker("", selection: $isPrivate) {
                        Text("meshcore.join.hashtag".localized).tag(false)
                        Text("meshcore.join.private".localized).tag(true)
                    }
                    .pickerStyle(.segmented)
                    TextField(isPrivate ? "meshcore.join.name".localized : "#paris", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if isPrivate {
                        TextField("meshcore.join.secret".localized, text: $secret)
                            .font(.body.monospaced())
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                } footer: {
                    Text((isPrivate ? "meshcore.join.private.footer" : "meshcore.join.hashtag.footer").localized)
                }
                if failed {
                    Label("meshcore.noFreeChannel".localized, systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundColor(.orange)
                }
            }
            .navigationTitle("meshcore.channel.join".localized)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("cancel".localized) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("meshcore.join.button".localized) {
                        guard let p = parsed else { return }
                        if meshCore.joinChannel(name: p.name, secret: p.secret) { dismiss() } else { failed = true }
                    }
                    .disabled(parsed == nil)
                }
            }
        }
    }
}
