//
//  LoRaNodeSetupView.swift
//  OSHI — the screen that turns a freshly flashed node into a working one.
//
//  It exists for one measured reason. A node straight from the flasher boots
//  with `region=UNSET` and will not transmit:
//
//      INFO | Wanted region 0, using UNSET
//
//  It pairs over Bluetooth, reports "connected", and silently sends nothing.
//  Anyone carrying that node in their pocket would believe they were on the mesh
//  and would not be. So this screen refuses to call a node ready until it has a
//  region, and says exactly that in plain words.
//
//  It configured a radio and nothing else until 2026-10-20, when the two
//  routing preferences landed at the bottom of it — see `routingSection`. They
//  belong on this screen because both are meaningless without a paired node,
//  and a setting you cannot act on does not belong in a settings list. Nothing
//  here touches encryption or the mesh protocol.
//

import SwiftUI

struct LoRaNodeSetupView: View {
    @ObservedObject private var manager = MeshtasticManager.shared

    /// Nil until the user picks. Pre-filled from the device's locale as a
    /// SUGGESTION, never applied on its own — which band a radio may use is a
    /// legal question, and the phone's locale is not proof of where the node is.
    @State private var chosenRegion: LoRaRegion?
    @State private var preset: LoRaModemPreset = .longFast
    @State private var didApply = false
    @State private var showChileConfirmation = false

    /// Which row the person actually tapped, so that row — and only that row —
    /// shows the spinner.
    @State private var connectingID: UUID?
    @State private var connectingName: String?

    /// __NEVER_OVER_THE_INTERNET_2026_10_20__ Bound to the raw keys rather than
    /// to `TransportPolicy`'s computed pair, because `@AppStorage` needs a
    /// storage key; the policy owns the meaning of both, and the names are
    /// deliberately the stored sense (DISABLED), not the reading sense.
    /// __RENAME_FROM_THE_APP_2026_10_21__ Le nom du nœud, modifiable ici.
    ///
    /// Vides jusqu'au premier `onAppear` où le nœud est connu: on ne veut pas
    /// préremplir avec du vide puis écraser ce que l'utilisateur vient de taper
    /// quand le NodeInfo arrive une seconde plus tard.
    @State private var editLongName = ""
    @State private var editShortName = ""
    @State private var namesLoaded = false
    @State private var renameSaved = false

    // __LORA_CHANNEL_2026_10_26__ Which MESH the node is on. The drafts start
    // at what this phone last wrote, NOT at what the node holds: nothing reads
    // a channel back off the radio, and showing an invented value as the node's
    // state would be worse than showing none.
    @State private var channelName = ""
    @State private var channelKey = ""
    @State private var channelKeyInvalid = false
    @State private var channelWritten = false
    @State private var channelLoaded = false
    // __PUBLIC_CHANNELS_2026_11_04__
    @State private var pastedLink = ""
    @State private var pastedChannelSet: LoRaChannelSet.Parsed?
    @State private var pastedLinkInvalid = false
    @State private var joinedChannelName: String?
    @State private var linkCopied = false
    /// __LORA_CONFIGURE_DISCLOSURE_2026_11_04__ Collapsed by default.
    @State private var configureOpen = false

    @AppStorage(TransportPolicy.internetSendingDisabledKey) private var internetSendingDisabled = false
    @AppStorage(TransportPolicy.preferRadioOverRelayKey) private var preferRadioOverRelay = false
    @AppStorage(MeshtasticManager.positionBeaconKey) private var positionBeacon = false

    private let brand = Theme.brand

    /// The node is connected but muted — the state that looks like success and
    /// behaves like failure.
    private var needsRegion: Bool {
        manager.isReady && (manager.region == nil || manager.region == .unset)
    }

    var body: some View {
        List {
            statusSection

            // __ANTI_JAMMING_PHASE1_2026_11_13__ Right under the status: a jammed
            // channel is part of the answer to "is my radio working?".
            LoRaJammingWarningSection()

            if needsRegion || didApply {
                regionSection
            }

            if manager.isReady, let r = manager.region, r != .unset {
                readySection(r)
            }

            if !manager.isReady {
                discoverySection
            }

            // __LORA_NODE_LIST_2026_11_04__ Second, right under the status,
            // because "who can I reach" is the other half of what this screen is
            // opened for. There was no list of nodes anywhere in the app before
            // this: `manager.nodes.count` as a number, and pins on the map.
            if manager.isReady {
                loRaNodesSection
            }

            if manager.isReady {
                channelSection
                // __PUBLIC_CHANNELS_2026_11_04__ The node's own answer, then the
                // two things a user actually does with a public channel: join one
                // somebody shared, and share their own.
                nodeChannelsSection
                joinChannelSection
                shareChannelSection
            }

            // __LORA_CONFIGURE_DISCLOSURE_2026_11_04__ Pairing, node name and
            // routing are all real settings and none of them is what this screen
            // is opened for. They used to sit in the same flat stack as the
            // status, which is how a status screen became a ten-section form.
            // Collapsed by default, nothing removed: the same sections, one tap
            // away. Progressive disclosure rather than everything up front.
            if manager.isReady {
                configureDisclosureSection
            }

            if manager.isReady && configureOpen {
                pairingSection
                renameSection
            }

            // Inside the disclosure once connected; on its own when not, because
            // then there is no disclosure to put it in and these two switches are
            // still the only thing on this screen a user can act on.
            if !manager.isReady || configureOpen {
                routingSection
            }

            buyNodeSection

            guideSection
        }
        .navigationTitle("lora.node.title".localized)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: manager.myNodeNum) { _, _ in loadNamesIfNeeded() }
        .onChange(of: manager.isReady) { _, _ in
            loadNamesIfNeeded()
            loadChannelIfNeeded()
        }
        .onAppear {
            loadNamesIfNeeded()
            loadChannelIfNeeded()
            if chosenRegion == nil { chosenRegion = LoRaRegion.suggestedFromDevice }
            if !manager.isReady { manager.startScan() }
        }
        .onDisappear { manager.stopScan() }
        .onChange(of: manager.state) { newState in
            // Attempt over, either way — stop pointing at a row.
            if newState == .ready || newState == .off {
                connectingID = nil
                connectingName = nil
            }
            // A radio that accepts the BLE link but never answers leaves the
            // screen spinning forever. Say so instead.
            if newState == .connecting || newState == .configuring {
                let attempt = newState
                DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
                    guard manager.state == attempt else { return }
                    manager.lastError = "lora.error.timeout".localized
                    manager.disconnect()
                    manager.startScan()
                }
            }
        }
    }

    // MARK: Status

    private var statusSection: some View {
        Section {
            HStack(spacing: 14) {
                ZStack {
                    if isBusy {
                        ProgressView()
                    } else {
                        Image(systemName: statusIcon)
                            .font(.scaled(26))
                            .foregroundColor(statusColor)
                    }
                }
                .frame(width: 34)
                .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(statusTitle).font(.headline)
                    Text(statusDetail)
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)

            if manager.isReady {
                LabeledContent("lora.node.name".localized,
                               value: manager.displayName ?? "—")
                LabeledContent("lora.node.id".localized,
                               value: manager.myNodeNum == 0 ? "—"
                                    : String(format: "!%08x", manager.myNodeNum))
                // __LORA_HERO_2026_11_04__ The channel belongs here: "am I
                // connected, and to WHICH mesh" is one question, and the answer
                // used to be several sections apart.
                LabeledContent("lora.hero.channel".localized,
                               value: manager.nodeChannels.first(where: { $0.isPrimary })?.displayName
                                    ?? LoRaChannel.displayName(manager.channelName))
                // "3 now, 11 total" rather than "11": a count of every radio ever
                // heard answers a different question from the one being asked,
                // which is who is within reach right now.
                LabeledContent(
                    "lora.hero.heard".localized,
                    value: String(format: "lora.hero.heard_present".localized,
                                  manager.nodes.filter { $0.isPresent() }.count,
                                  manager.nodes.count))
            }
        }
    }

    /// The tap on a discovered node used to change nothing on screen: `.connecting`
    /// and `.configuring` rendered the same "Looking for a node" as `.off`, and a
    /// failed connect only set `lastError`, which nothing displayed. Both now have
    /// their own state, so a tap always visibly does something.
    private var isBusy: Bool {
        manager.state == .connecting || manager.state == .configuring
    }

    private var statusIcon: String {
        if isBusy { return "antenna.radiowaves.left.and.right" }
        if !manager.isReady { return "antenna.radiowaves.left.and.right.slash" }
        return needsRegion ? "exclamationmark.triangle.fill" : "antenna.radiowaves.left.and.right"
    }
    private var statusColor: Color {
        if isBusy { return brand }
        if !manager.isReady { return .secondary }
        return needsRegion ? .orange : .green
    }
    private var statusTitle: String {
        if manager.state == .connecting { return "lora.status.connecting".localized }
        if manager.state == .configuring { return "lora.status.configuring".localized }
        if !manager.isReady { return "lora.status.searching".localized }
        return needsRegion ? "lora.status.needs_region".localized
                           : "lora.status.ready".localized
    }
    private var statusDetail: String {
        if manager.state == .connecting {
            return String(format: "lora.status.connecting.detail".localized,
                          connectingName ?? "lora.node.unnamed".localized)
        }
        if manager.state == .configuring { return "lora.status.configuring.detail".localized }
        if !manager.isReady { return "lora.status.searching.detail".localized }
        if needsRegion { return "lora.status.needs_region.detail".localized }
        if !manager.transmitEnabled { return "lora.status.tx_off.detail".localized }
        return "lora.status.ready.detail".localized
    }

    // MARK: Region

    private var regionSection: some View {
        Section {
            Picker("lora.region.picker".localized,
                   selection: Binding(
                    get: { chosenRegion ?? .eu868 },
                    set: { chosenRegion = $0 })) {
                ForEach(LoRaRegion.selectable) { r in
                    Text(r.displayName).tag(r)
                }
            }

            Picker("lora.preset.picker".localized, selection: $preset) {
                ForEach(LoRaModemPreset.allCases) { p in
                    VStack(alignment: .leading) {
                        Text(p.displayName)
                        Text(p.detail).font(.caption).foregroundColor(.secondary)
                    }.tag(p)
                }
            }

            Button("lora.chile.apply".localized) {
                showChileConfirmation = true
            }
            .disabled(!manager.isReady)
            Text("lora.chile.detail".localized)
                .font(.caption)
                .foregroundColor(.secondary)

            Button {
                guard let r = chosenRegion else { return }
                if manager.setRegion(r, preset: preset) { didApply = true }
            } label: {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                    Text("lora.region.apply".localized)
                }
            }
            .disabled(chosenRegion == nil || !manager.isReady)
        } header: {
            Text("lora.region.section".localized)
        } footer: {
            // The legal point, stated once, without lecturing.
            Text("lora.region.footer".localized).font(.caption2)
        }
        .confirmationDialog("lora.chile.confirm.title".localized,
                            isPresented: $showChileConfirmation,
                            titleVisibility: .visible) {
            Button("lora.chile.confirm.apply".localized, role: .destructive) {
                if manager.setChileMeshDefaults() {
                    chosenRegion = .us
                    preset = .longFast
                    didApply = true
                }
            }
        } message: {
            Text("lora.chile.confirm.body".localized)
        }
    }

    @ViewBuilder
    private func readySection(_ r: LoRaRegion) -> some View {
        Section {
            LabeledContent("lora.node.region".localized, value: r.displayName)
            if !manager.transmitEnabled {
                Label("lora.status.tx_off".localized, systemImage: "exclamationmark.triangle.fill")
                    .foregroundColor(.orange)
            }
            Button("lora.region.change".localized) { didApply = true }
        } header: {
            Text("lora.node.section.radio".localized)
        }

        // __MAP_VISIBILITY_OPT_IN_2026_11_09__ Setup never mentioned the position
        // beacon (its only switch was in LoRa settings), so a freshly paired node
        // was invisible on every map, Meshtastic's and OSHI's. Surfaced here as the
        // SAME explicit opt-in, same stored key, default OFF: it is a public
        // cleartext location broadcast to every radio in range, never automatic.
        Section {
            Toggle("lora.position.toggle".localized, isOn: $positionBeacon)
                .onChange(of: positionBeacon) { _, on in
                    if on { LoRaPositionBeacon.shared.switchTurnedOn() }
                }
        } header: {
            Text("lora.position.section".localized)
        } footer: {
            Text("lora.position.footer".localized)
        }
    }

    // MARK: Name

    /// Celui des deux noms que le nœud garde pour lui, et celui que tout le
    /// monde voit.
    private func loadNamesIfNeeded() {
        guard !namesLoaded, manager.isReady, manager.myNodeNum != 0,
              let mine = manager.nodes.first(where: { $0.num == manager.myNodeNum }) else { return }
        editLongName = mine.longName
        editShortName = mine.shortName
        namesLoaded = true
    }

    /// __RENAME_FROM_THE_APP_2026_10_21__ `setOwner` existait et fonctionnait —
    /// `renameIfFactoryDefault` s'en sert — mais rien n'y menait depuis l'app:
    /// pour appeler son nœud autrement que « OSHI Mesh 23D8 » il fallait passer
    /// par un autre client Meshtastic.
    private var renameSection: some View {
        Section {
            TextField(NSLocalizedString("lora.name.long", value: "Name",
                                        comment: "The node's long name, shown to everyone"),
                      text: $editLongName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            VStack(alignment: .leading, spacing: 4) {
                TextField(NSLocalizedString("lora.name.short", value: "Short name",
                                            comment: "The node's 4-byte short name"),
                          text: $editShortName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    // Coupe PENDANT la frappe, pas à l'enregistrement: laisser
                    // taper cinq caractères puis en jeter deux en silence est
                    // la version désagréable du même plafond.
                    .onChange(of: editShortName) { _, new in
                        let clamped = MeshtasticManager.clampShortName(new)
                        if clamped != new { editShortName = clamped }
                    }
                Text(String(format: NSLocalizedString(
                        "lora.name.short.budget",
                        value: "%d of 4 bytes — three or four letters, or one emoji",
                        comment: "Byte budget under the short-name field"),
                        editShortName.utf8.count))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Button {
                renameSaved = manager.setOwner(longName: editLongName,
                                               shortName: editShortName)
            } label: {
                Text(NSLocalizedString("lora.name.save", value: "Save name",
                                       comment: "Button that writes the name to the node"))
            }
            .disabled(editLongName.trimmingCharacters(in: .whitespaces).isEmpty)

            if renameSaved {
                Label(NSLocalizedString("lora.name.saved",
                                        value: "Sent to the node. Other clients pick it up on the next announcement.",
                                        comment: "Confirmation after writing the node name"),
                      systemImage: "checkmark.circle")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        } header: {
            Text(NSLocalizedString("lora.name.section", value: "NODE NAME",
                                   comment: "Section header for renaming the node"))
        } footer: {
            Text(NSLocalizedString(
                    "lora.name.footer",
                    value: "The short name is what fits on the node's own display and in other clients' lists. Meshtastic allows four BYTES there, so one emoji uses the whole budget.",
                    comment: "Explains the 4-byte short-name limit"))
        }
    }

    // MARK: Routing

    /// Where a message goes, and whether it may use the internet at all.
    ///
    /// Both rows say what they COST, not just what they do. "Prefer my radio"
    /// without "photos still use the network" reads as a privacy switch and
    /// behaves like a speed limit; "never send over the internet" without
    /// "incoming messages still arrive that way" claims more than the code
    /// delivers. Overselling either one is the way this feature becomes a
    /// complaint.
    private var routingSection: some View {
        Section {
            Toggle(isOn: $preferRadioOverRelay) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(NSLocalizedString("lora.routing.prefer_radio",
                                           value: "Prefer my radio over the internet",
                                           comment: "Toggle: send text over LoRa before the relay"))
                    Text(NSLocalizedString("lora.routing.prefer_radio.note",
                                           value: "Text messages go out over LoRa first. Photos and files keep using the network when one is available — over the radio they would take minutes.",
                                           comment: "Explains the limit of preferring LoRa"))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            Toggle(isOn: $internetSendingDisabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(NSLocalizedString("lora.routing.no_internet",
                                           value: "Never send over the internet",
                                           comment: "Toggle: forbid the relay for outgoing traffic"))
                    Text(NSLocalizedString("lora.routing.no_internet.note",
                                           value: "Nothing you send touches the relay — not a message, not a read receipt. Anything that cannot go by radio or to a nearby phone waits as pending. Incoming messages still arrive over the internet.",
                                           comment: "Explains the exact scope of the no-internet switch"))
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            // The state someone WILL get into: switch on, no node paired, and
            // then wonder why nothing sends. Say it here rather than let them
            // find out from a column of pending messages.
            if internetSendingDisabled && !manager.isReady {
                Label(NSLocalizedString("lora.routing.no_route_warning",
                                        value: "No radio is paired, so nothing can be sent until one is — or until a phone running OSHI is close enough for the mesh.",
                                        comment: "Warning: no-internet is on with no offline route available"),
                      systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundColor(.orange)
            }
        } header: {
            Text(NSLocalizedString("lora.routing.section", value: "Routing",
                                   comment: "Section header for transport routing preferences"))
        }
    }

    // MARK: Discovery

    private var discoverySection: some View {
        Section {
            if manager.permissionDenied {
                Label("lora.error.bluetooth_denied".localized,
                      systemImage: "exclamationmark.triangle.fill")
                    .foregroundColor(.orange)
            } else if !manager.bluetoothOn {
                Label("lora.error.bluetooth_off".localized, systemImage: "bolt.slash")
                    .foregroundColor(.secondary)
            } else if manager.discovered.isEmpty {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("lora.discovery.searching".localized)
                        .foregroundColor(.secondary)
                }
            } else {
                // __PIN_WITHOUT_A_SCREEN_2026_10_21__ Le moment EXACT ou on en a
                // besoin.
                //
                // iOS réclame un code à 6 chiffres que le nœud affiche sur son
                // écran — sauf que plusieurs cartes n'en ont aucun, et la
                // réponse (123456) se trouvait seulement sur un forum. L'option
                // « Sans code » existe dans cet écran mais vit sous
                // `if manager.isReady`: elle n'apparaît qu'une fois CONNECTÉ,
                // donc elle ne peut pas débloquer un premier appairage. C'est
                // inévitable — ce réglage est une config du nœud, écrite par la
                // liaison Bluetooth — mais laisser l'utilisateur sans indice ne
                // l'est pas.
                //
                // La nuance du « premier démarrage » est dite, parce qu'elle
                // explique les échecs: le mode est fixé selon qu'un écran était
                // présent au tout premier démarrage, et le retirer ensuite ne le
                // change pas.
                Label(NSLocalizedString(
                        "lora.pairing.noscreen.hint",
                        value: "No screen on your node? When iOS asks for a code, try 123456 — that is the default on boards that had no display the first time they booted.",
                        comment: "Hint shown while scanning: the default pairing code for screenless nodes"),
                      systemImage: "info.circle")
                    .font(.caption)
                    .foregroundColor(.secondary)

                ForEach(manager.discovered, id: \.peripheral.identifier) { found in
                    let isTarget = connectingID == found.peripheral.identifier
                    Button {
                        // Clear the previous attempt's error, otherwise a stale
                        // failure sits under a fresh, working connection.
                        manager.lastError = nil
                        connectingID = found.peripheral.identifier
                        connectingName = found.peripheral.name
                        manager.connect(found.peripheral)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(found.peripheral.name ?? "lora.node.unnamed".localized)
                                Text(signalWord(found.rssi))
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            if isTarget && isBusy {
                                ProgressView()
                            } else {
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                    .disabled(isBusy)
                }
            }

            if let err = manager.lastError, !err.isEmpty {
                Label(err, systemImage: "exclamationmark.circle.fill")
                    .font(.caption)
                    .foregroundColor(.orange)
            }
        } header: {
            Text("lora.discovery.section".localized)
        } footer: {
            Text("lora.discovery.footer".localized).font(.caption2)
        }
    }

    // MARK: Pairing

    /// A node ships asking for a 6-digit code shown on its own screen. That is
    /// the safer default and stays the default — but plenty of boards have no
    /// screen to read it off, and on the ones that do it turns pairing into
    /// squint-and-type. Offer the choice instead of deciding for everyone.
    // MARK: Channel

    /// Pre-filled once, from what this phone last wrote.
    private func loadChannelIfNeeded() {
        guard !channelLoaded else { return }
        channelName = manager.channelName
        channelLoaded = true
    }

    /// The channel section: a name, an optional key, one button that writes
    /// both. Both fields empty is NOT an empty form, it is the stock LongFast
    /// channel, so the button stays enabled on an empty name — that is the "put
    /// me back on the normal mesh" case. [LoRa channel 2026-10-26]
    private var channelSection: some View {
        Section {
            TextField(LoRaChannel.defaultName,
                      text: Binding(
                        get: { channelName },
                        // Clamped WHILE TYPING: the firmware's name field is 11
                        // BYTES, and letting a 12th be typed only to drop it on
                        // write is the unpleasant version of the same ceiling.
                        set: {
                            channelName = LoRaChannel.clampName($0)
                            channelWritten = false
                        }))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            Text(String(format: "lora.channel.name_budget".localized,
                        channelName.utf8.count, LoRaChannel.nameMaxBytes))
                .font(.caption2)
                .foregroundColor(.secondary)

            TextField("lora.channel.key_default".localized,
                      text: Binding(
                        get: { channelKey },
                        set: {
                            channelKey = $0
                            channelKeyInvalid = false
                            channelWritten = false
                        }))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .font(.system(.body, design: .monospaced))

            if channelKeyInvalid {
                Label("lora.channel.key_invalid".localized,
                      systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundColor(.orange)
            } else {
                Text("lora.channel.key_hint".localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Button {
                // A key that is not a key REFUSES rather than falling back to
                // the public default: silently publishing to the open channel
                // under a key the user believed was theirs is the one outcome
                // worth a line of orange.
                guard let psk = LoRaChannel.parsePSK(channelKey) else {
                    channelKeyInvalid = true
                    channelWritten = false
                    return
                }
                channelWritten = manager.setChannel(name: channelName, psk: psk)
            } label: {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                    Text("lora.channel.apply".localized)
                }
            }
            .disabled(!manager.isReady)

            if channelWritten {
                Text(String(format: "lora.channel.written".localized,
                            LoRaChannel.displayName(channelName)))
                    .font(.caption)
                    .foregroundColor(.green)
            } else if !manager.channelName.isEmpty {
                // "What this phone asked for last time". The node's OWN answer
                // now has a section of its own —
                // __NODE_CHANNELS_WERE_DROPPED_2026_11_04__ — so this line no
                // longer has to stand in for it.
                Text(String(format: "lora.channel.last".localized, manager.channelName))
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        } header: {
            Text("lora.channel.section".localized)
        } footer: {
            Text("lora.channel.footer".localized).font(.caption2)
        }
    }

    /// __PUBLIC_CHANNELS_2026_11_04__ What the NODE says it is on.
    ///
    /// Distinct from the section above, which shows what this phone last wrote.
    /// Until field 10 of `FromRadio` was handled, the two could not be told apart
    /// because only one of them existed.
    @ViewBuilder
    private var nodeChannelsSection: some View {
        Section {
            if manager.nodeChannels.isEmpty {
                // Said plainly rather than left blank: an empty list here means
                // the handshake has not happened, not that the node has no
                // channels, and those two read identically if nobody says which.
                Text("lora.channels.none".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
            } else {
                ForEach(manager.nodeChannels) { channel in
                    // __LORA_CHANNEL_IS_A_ROOM_2026_11_05__ The row OPENS the channel.
                    //
                    // It listed the radio's channels and did nothing with them, so the
                    // only way into a channel's conversation was to wait for someone
                    // else to speak in it and then find the thread in the message list.
                    // You could not say the first word in a room you were already on.
                    //
                    // `OpenChat` takes a peer key and a channel key is exactly that
                    // shape — `lorachan!manif` or `lorachan#3`, the same key the
                    // receive path files broadcasts under. So the room this opens is
                    // the room the messages arrive in, by construction rather than by
                    // coincidence.
                    Button {
                        let key = LoRaChannelThread.key(
                            channelIndex: channel.index,
                            channelName: channel.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                ? nil : channel.name
                        )
                        NotificationCenter.default.post(
                            name: NSNotification.Name("OpenChat"),
                            object: nil,
                            userInfo: ["peerPublicKey": key]
                        )
                    } label: {
                    HStack(alignment: .top) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(channel.isPrimary ? .green : .secondary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(channel.displayName)
                            // The key's NATURE, not the key. Someone at a protest
                            // needs "anyone can read this" at a glance, and the
                            // public default key is exactly that despite the
                            // padlock other apps draw beside it.
                            Text(keyLabel(for: channel.psk))
                                .font(.caption2)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Text((channel.isPrimary
                              ? "lora.channels.primary"
                              : "lora.channels.secondary").localized)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                        // The affordance. Without it the row is tappable and
                        // nothing says so, which is the fault this change exists
                        // to remove rather than relocate.
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundColor(.secondary.opacity(0.6))
                    }
                    }
                    .buttonStyle(.plain)
                }
            }
        } header: {
            Text("lora.channels.section".localized)
        } footer: {
            Text("lora.channels.footer".localized).font(.caption2)
        }
    }

    private func keyLabel(for psk: Data) -> String {
        if psk.isEmpty || (psk.count == 1 && psk.first == 0) {
            return "lora.channels.no_key".localized
        }
        if psk.count == 1 && psk.first == 1 {
            return "lora.channels.public_key".localized
        }
        return "lora.channels.private_key".localized
    }

    /// Joining a channel somebody shared as a link, which is how every public
    /// mesh is actually handed out.
    private var joinChannelSection: some View {
        Section {
            TextField("https://meshtastic.org/e/#",
                      text: Binding(
                        get: { pastedLink },
                        set: {
                            pastedLink = $0
                            pastedChannelSet = LoRaChannelSet.parse($0)
                            pastedLinkInvalid = !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                && pastedChannelSet == nil
                            joinedChannelName = nil
                        }))
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .font(.system(.caption, design: .monospaced))

            if pastedLinkInvalid {
                Label("lora.join.invalid".localized, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundColor(.orange)
            } else if let parsed = pastedChannelSet {
                // Shown BEFORE the write, because a channel link is opaque: the
                // user cannot tell what they are about to join by looking at it,
                // and joining takes the radio off its current mesh.
                let primary = parsed.primary?.displayName ?? ""
                let extra = parsed.secondaries.count
                Text(extra == 0
                     ? String(format: "lora.join.preview_one".localized, primary)
                     : String(format: "lora.join.preview_many".localized, primary, extra))
                    .font(.caption)
            } else {
                Text("lora.join.replaces".localized)
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }

            Button {
                guard let parsed = pastedChannelSet else { return }
                let name = parsed.primary?.displayName
                if manager.joinChannelSet(parsed) {
                    joinedChannelName = name
                    pastedLink = ""
                    pastedChannelSet = nil
                }
            } label: {
                HStack {
                    Image(systemName: "checkmark.circle.fill")
                    Text("lora.join.apply".localized)
                }
            }
            .disabled(!manager.isReady || pastedChannelSet == nil)

            if let joined = joinedChannelName {
                Text(String(format: "lora.join.done".localized, joined))
                    .font(.caption)
                    .foregroundColor(.green)
            }
        } header: {
            Text("lora.join.section".localized)
        } footer: {
            Text("lora.join.footer".localized).font(.caption2)
        }
    }

    /// Handing your own mesh to the people with you.
    @ViewBuilder
    private var shareChannelSection: some View {
        if let url = manager.shareableChannelURL() {
            Section {
                // The link itself is shown, not hidden behind the button: it
                // carries the channel key, and someone about to hand it out
                // should see what they are handing out.
                Text(url)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundColor(.secondary)
                    .textSelection(.enabled)

                Button {
                    #if canImport(UIKit)
                    UIPasteboard.general.string = url
                    #endif
                    linkCopied = true
                } label: {
                    HStack {
                        Image(systemName: "doc.on.doc")
                        Text("lora.share.copy".localized)
                    }
                }

                if linkCopied {
                    Text("lora.share.copied".localized)
                        .font(.caption)
                        .foregroundColor(.green)
                }
            } header: {
                Text("lora.share.section".localized)
            } footer: {
                Text("lora.share.footer".localized).font(.caption2)
            }
        }
    }

    /// __LORA_CONFIGURE_DISCLOSURE_2026_11_04__ The one tap that opens the
    /// settings this screen used to lead with.
    ///
    /// A row with a turning chevron rather than a bare header: a collapsed
    /// section nobody can see how to open is worse than a flat list.
    private var configureDisclosureSection: some View {
        Section {
            Button {
                withAnimation(.easeOut(duration: 0.2)) { configureOpen.toggle() }
            } label: {
                HStack {
                    Text("lora.configure.section".localized)
                        .foregroundColor(.primary)
                    Spacer()
                    Text((configureOpen ? "lora.configure.hide" : "lora.configure.show").localized)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .rotationEffect(.degrees(configureOpen ? 90 : 0))
                }
            }
            .accessibilityAddTraits(configureOpen ? [] : .isButton)
            .accessibilityValue(configureOpen ? "Expanded" : "Collapsed")
        }
    }

    /// __LORA_NODE_LIST_2026_11_04__ Every radio this node has heard.
    ///
    /// Ordered most-recently-heard first: in the field the useful question is who
    /// is near me NOW, and an order by node number answers nothing.
    @ViewBuilder
    private var loRaNodesSection: some View {
        Section {
            if manager.nodes.isEmpty {
                // Said plainly rather than left blank: an empty list here means
                // the handshake has not produced a neighbour yet, not that there
                // are none, and those two read identically if nobody says which.
                Text("lora.nodes.empty".localized)
                    .font(.caption)
                    .foregroundColor(.secondary)
            } else {
                ForEach(manager.nodes.sorted { $0.lastHeard > $1.lastHeard }) { node in
                    loRaNodeRow(node)
                }
            }
        } header: {
            Text("lora.nodes.section".localized)
        } footer: {
            Text("lora.nodes.footer".localized).font(.caption2)
        }
    }

    @ViewBuilder
    private func loRaNodeRow(_ node: MeshtasticNode) -> some View {
        let name = node.longName.isEmpty
            ? (node.shortName.isEmpty ? node.userId : node.shortName)
            : node.longName
        HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(name)
                        .lineLimit(1)
                    if node.num == manager.myNodeNum {
                        Text("lora.nodes.me_badge".localized)
                            .font(.caption2)
                            .foregroundColor(.green)
                    }
                }
                Text(nodeDetailLine(node))
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            if node.rssi != nil {
                VStack(alignment: .trailing, spacing: 2) {
                    signalBars(node.signalBars)
                    // The figure beside the bars: a shape alone is no more
                    // readable than a colour alone.
                    Text("\(node.rssi!) dBm")
                        .font(.caption2)
                        .monospacedDigit()
                        .foregroundColor(.secondary)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    String(format: "lora.nodes.signal_a11y".localized, node.signalBars))
            }
        }
        .padding(.vertical, 2)
    }

    /// Four bars, with the EMPTY ones drawn too: that is what makes it read as
    /// "1 of 4" rather than "one bar, out of who knows what".
    private func signalBars(_ bars: Int) -> some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(1...4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i <= bars ? Color.green : Color.secondary.opacity(0.25))
                    .frame(width: 3, height: CGFloat(5 + i * 3))
            }
        }
        .frame(height: 16)
    }

    /// "Direct  ·  2 min ago  ·  ~0.4-1.2 km" — only the parts we actually know.
    ///
    /// Joined from the pieces that exist rather than printing placeholders: most
    /// boards have no GPS (a Heltec V3 has none), so a row that always showed a
    /// distance would be showing a guess as a fact.
    private func nodeDetailLine(_ node: MeshtasticNode) -> String {
        var parts: [String] = []

        if let hops = node.hopsAway {
            parts.append(hops == 0
                ? "lora.nodes.direct".localized
                : String(format: "lora.nodes.hops".localized, hops))
        }
        if let heard = lastHeardLabel(node.lastHeard) {
            parts.append(heard)
        }
        if node.hasPosition {
            parts.append("lora.nodes.on_map".localized)
        } else if let range = node.estimatedRangeKm {
            // Only when there is no real position: a node that reports where it
            // is does not need its distance guessed from signal strength.
            parts.append(String(format: "lora.nodes.range_estimate".localized,
                                String(format: "%.1f", range.min),
                                String(format: "%.1f", range.max)))
        }
        return parts.joined(separator: "  \u{00B7}  ")
    }

    /// Bucketed, not exact, and nil for a radio never heard from.
    ///
    /// The gap between "30 s ago" and "50 s ago" carries nothing a person can act
    /// on; the gap between "now" and "yesterday" is the difference between a radio
    /// that is there and one that is gone. Exact figures here would also rewrite
    /// the row every second. Mirrors Android `LoRaFormat.lastHeardBucket`.
    private func lastHeardLabel(_ lastHeard: Date, now: Date = Date()) -> String? {
        let age = now.timeIntervalSince(lastHeard)
        // `Date.distantPast` is what a node we have never had a packet from
        // carries; formatting that gives "19592 d ago".
        guard age < 365 * 86_400 else { return nil }
        // A mesh board often has no RTC, so a lastHeard in the future is
        // ordinary. It was still heard just now.
        if age < 90 { return "lora.nodes.now".localized }
        let minutes = Int(age / 60)
        if minutes < 60 { return String(format: "lora.nodes.minutes".localized, minutes) }
        let hours = Int(age / 3600)
        if hours < 24 { return String(format: "lora.nodes.hours".localized, hours) }
        return String(format: "lora.nodes.days".localized, max(1, Int(age / 86_400)))
    }

    private var pairingSection: some View {
        Section {
            Picker("lora.pairing.picker".localized,
                   selection: Binding(
                    get: { manager.pairingMode ?? .randomPin },
                    set: { manager.setBluetoothPairing($0) })) {
                ForEach(MeshtasticManager.BluetoothPairing.allCases) { mode in
                    Text(mode.displayName).tag(mode)
                }
            }

            Text((manager.pairingMode ?? .randomPin).detail)
                .font(.caption)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        } header: {
            Text("lora.pairing.section".localized)
        } footer: {
            Text("lora.pairing.footer".localized).font(.caption2)
        }
    }

    // MARK: Guide

    /// Someone who has never held a LoRa board has no way to guess that the
    /// thing needs flashing from a desktop browser first. Without this the
    /// screen just says "Looking for a node" forever and never explains why
    /// nothing shows up.
    private var guideSection: some View {
        Section {
            ForEach(Array(guideSteps.enumerated()), id: \.offset) { i, step in
                HStack(alignment: .top, spacing: 12) {
                    Text("\(i + 1)")
                        .font(.caption.weight(.bold))
                        .foregroundColor(.white)
                        .frame(width: 22, height: 22)
                        .background(Circle().fill(brand))
                        .accessibilityHidden(true)
                    Text(step)
                        .font(.callout)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 2)
            }

            Link(destination: URL(string: "https://oshi-messenger.com/lora/flash/")!) {
                Label("lora.guide.link".localized, systemImage: "arrow.up.right.square")
            }
        } header: {
            Text("lora.guide.section".localized)
        } footer: {
            Text("lora.guide.footer".localized).font(.caption2)
        }
    }

    private var guideSteps: [String] {
        (1...5).map { "lora.guide.step\($0)".localized }
    }

    /// Words, not dBm. "-73" tells the person holding the phone nothing.
    private func signalWord(_ rssi: Int) -> String {
        switch rssi {
        case (-60)...:      return "lora.signal.excellent".localized
        case (-70)..<(-60): return "lora.signal.good".localized
        case (-85)..<(-70): return "lora.signal.fair".localized
        default:            return "lora.signal.weak".localized
        }
    }
}

// MARK: - Where a radio comes from

extension LoRaNodeSetupView {
    /// __LORA_BUY_LINK_2026_11_04__ The app discusses paired radios throughout
    /// and never said where one comes from.
    ///
    /// The page it opens is a pointer at measured information rather than a
    /// shop: nine boards verified on real hardware, each with the vendor's own
    /// price and a worldwide search link. "from US$18" is the cheapest
    /// VERIFIED board — Heltec V3, read off heltec.org at $17.90 — not a
    /// marketing floor. If that page stops agreeing, this number is wrong and
    /// must change with it.
    @ViewBuilder
    var buyNodeSection: some View {
        Section {
            Link(destination: URL(string: "https://oshi-messenger.com/lora/")!) {
                HStack(spacing: 12) {
                    Image(systemName: "cart")
                        .foregroundColor(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("lora.buy.node".localized)
                            .foregroundColor(.primary)
                        Text(String(format: "lora.buy.from".localized, "US$18"))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Image(systemName: "arrow.up.right.square")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}
