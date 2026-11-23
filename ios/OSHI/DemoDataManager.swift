//
//  DemoDataManager.swift
//  OSHI - Pre-populated demo data for App Store Review
//
//  Creates sample contacts, conversations, groups, and bot messages
//  so Apple reviewers can test all features without a second device.
//
//  Activated by entering the demo recovery key in "Import Existing Identity".
//

import Foundation
import UIKit

struct DemoDataManager {

    /// The special recovery key that activates demo mode for App Store Review.
    /// Entered in the "Import Existing Identity" field.
    static let demoRecoveryKey = "APPLE-REVIEW-2026"

    /// Check if the entered recovery key is the demo activation key.
    static func isDemoKey(_ input: String) -> Bool {
        return input.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() == demoRecoveryKey.uppercased()
    }

    // Deterministic fake public keys for demo contacts
    static let aliceKey = "DEMO_ALICE_aGVsbG8gZnJvbSBhbGljZSBkZW1vIGtleQ=="
    static let bobKey   = "DEMO_BOB_Ym9iIGRlbW8gcHVibGljIGtleSBmb3IgdGVzdA=="
    static let charlieKey = "DEMO_CHARLIE_Y2hhcmxpZSBkZW1vIGtleQ=="
    static let daveKey = "DEMO_DAVE_ZGF2ZSBkZW1vIHB1YmxpYyBrZXk="
    static let eveKey  = "DEMO_EVE_ZXZlIGRlbW8gcHVibGljIGtleQ=="
    static let frankKey = "DEMO_FRANK_ZnJhbmsgZGVtbyBwdWJsaWMga2V5"
    static let graceKey = "DEMO_GRACE_Z3JhY2UgZGVtbyBwdWJsaWMga2V5"
    static let henryKey = "DEMO_HENRY_aGVucnkgZGVtbyBwdWJsaWMga2V5"

    #if DEBUG
    /// __GROUP_OPEN_ANCHOR_2026_09_23__ `-OSHIGroupTrickle`: after the group is
    /// pushed, deliver messages in late batches (like a v2 pull / legacy history
    /// catch-up landing after the view appeared). The last one is tall, so the
    /// newest bubble only fits if the list really follows the bottom. DEBUG only.
    @MainActor
    static func trickleLateGroupMessages(into groupId: UUID) {
        let gm = MessageGroupManager.shared
        let batches: [(Double, [String])] = [
            (0.25, ["Late batch A1 (v2 pull)", "Late batch A2"]),
            (1.2,  ["Late batch B1", "Late batch B2", "Late batch B3"]),
            // Late enough (after the ~3 s landing loops) that only a follower
            // that keeps watching can bring it into view — and that a reader
            // can scroll up first to check it does NOT yank them.
            (9.0,  ["LAST MESSAGE — should be visible without scrolling. "
                    + String(repeating: "Tall bubble line. ", count: 12)]),
        ]
        var idx = 1000
        for (delay, texts) in batches {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                var list = gm.groupMessages[groupId] ?? []
                for t in texts {
                    idx += 1
                    list.append(GroupMessage(id: UUID(), groupId: groupId, senderPublicKey: bobKey,
                                             encryptedContent: Data(t.utf8), timestamp: Date(),
                                             isRead: true, messageChainIndex: idx, plaintextContent: t))
                }
                gm.groupMessages[groupId] = list
            }
        }
    }
    #endif

    #if DEBUG
    /// __SCROLL_LAB_2026_11_16__ `-OSHIIncomingAt "8,20"`: deliver one incoming
    /// message per listed delay (seconds after the call), to the group when
    /// `groupId` is given, else to the Alice thread — the "a message arrives
    /// while the conversation is open" check. Local only, no network.
    @MainActor
    static func scheduleScrollLabIncoming(groupId: UUID?, myKey: String) {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-OSHIIncomingAt"), i + 1 < args.count else { return }
        let delays = args[i + 1].split(separator: ",").compactMap { Double($0) }
        for (n, delay) in delays.enumerated() {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                let text = "INCOMING \(n + 1): je viens d'arriver, tu es où ?\n"
                    + "Je t'attends à l'entrée principale, à côté du panneau bleu.\nEND-INCOMING-\(n + 1)"
                if let gid = groupId {
                    let gm = MessageGroupManager.shared
                    var list = gm.groupMessages[gid] ?? []
                    list.append(GroupMessage(id: UUID(), groupId: gid, senderPublicKey: bobKey,
                                             encryptedContent: Data(text.utf8), timestamp: Date(),
                                             isRead: true, messageChainIndex: 5_000 + n, plaintextContent: text))
                    gm.groupMessages[gid] = list
                } else if let mm = MessageManager.shared {
                    var m = createDemoConversation(messages: [(text, false, 0)], myKey: myKey, myAddress: "",
                                                   peerKey: aliceKey, peerName: "Alice", now: Date())[0]
                    m.isRead = false
                    mm.addMessage(m)
                }
            }
        }
    }
    #endif

    static func loadDemoData(identityManager: IdentityManager, regenerateIdentity: Bool = true) {
        OshiLog.storage.info("🎭 Loading demo data for App Store Review...")

        // 1. Identity. Default path (App Store review) regenerates a clean identity.
        // Screenshot path passes regenerateIdentity:false so it reuses the already-
        // created identity and does NOT trigger connectWallet's wipe — which raced the
        // manager wiring and left the conversations empty when seeded at launch.
        if regenerateIdentity || !identityManager.isConnected {
            identityManager.connectWallet()
        }
        let myKey = identityManager.publicKey
        let myAddress = identityManager.identityAddress

        // 2. Skip onboarding
        UserDefaults.standard.set(true, forKey: "hasSeenOnboarding")
        UserDefaults.standard.set(true, forKey: "hasCompletedFirstTimeGuide")
        UserDefaults.standard.set(6, forKey: "currentGuideStep")

        // 3. Set contact aliases
        let aliases: [String: String] = [
            aliceKey: "Alice",
            bobKey: "Bob",
            charlieKey: "Charlie",
            daveKey: "Dave",
            eveKey: "Eve",
            frankKey: "Frank",
            graceKey: "Grace",
            henryKey: "Henry"
        ]
        if let data = try? JSONEncoder().encode(aliases) {
            UserDefaults.standard.set(data, forKey: "contactAliases")
        }

        // 3b. Real contact photos — makes the demo conversations feel personal
        // in App Store screenshots (bundled demo_*.jpg avatars). Screenshot/review
        // only path; harmless if the images aren't present.
        let avatarNames: [String: String] = [
            aliceKey: "demo_alice", bobKey: "demo_bob", charlieKey: "demo_charlie",
            daveKey: "demo_dave", eveKey: "demo_eve",
            frankKey: "demo_frank", graceKey: "demo_grace", henryKey: "demo_henry"
        ]
        for (key, imgName) in avatarNames {
            if let url = Bundle.main.url(forResource: imgName, withExtension: "jpg"),
               let data = try? Data(contentsOf: url),
               let img = UIImage(data: data) {
                _ = ContactPhotoManager.shared.savePhoto(img, for: key)
            }
        }

        // 4. Create 1-on-1 messages
        let now = Date()
        var allMessages: [SecureMessage] = []

        // Alice conversation — about encryption and privacy
        let aliceMessages: [(String, Bool, TimeInterval)] = [
            ("Hey! Welcome to OSHI. All our messages are end-to-end encrypted.", false, -7200),
            ("Thanks Alice! How does the encryption work?", true, -6900),
            ("Each user has a unique keypair. Messages are encrypted with Curve25519 + AES-256-GCM. Not even the server can read them.", false, -6600),
            ("That's impressive. Can I also make voice and video calls?", true, -6000),
            ("Yes! Voice and video calls are fully encrypted too. Tap the phone or camera icon in any chat.", false, -5400),
            ("I just tried the voice call, the audio quality is excellent!", true, -4800),
            ("Glad you like it! We use AAC-ELD codec at 48kHz — same codec as FaceTime.", false, -4500),
            ("And this all works without a phone number, right?", true, -3600),
            ("Exactly — no phone number, no email, no personal data. Your identity is just a cryptographic key.", false, -3300),
            ("Love it. Can I send photos and files securely too?", true, -2400),
            ("Yes — every photo, file and voice note is encrypted before it leaves your device. Even offline over Bluetooth mesh.", false, -2100),
            ("This is exactly what I've been looking for. 🔒", true, -900),
        ]
        #if DEBUG
        // __SCROLL_LAB_2026_11_16__ `-OSHIScrollLab`: a realistic Alice thread
        // (90 messages over four days, tall multi-paragraph bubbles, links,
        // photos) for the open / keyboard / send / receive scroll checks.
        // `-OSHIScrollUnread` leaves the last 40 (all from Alice) unread.
        if ProcessInfo.processInfo.arguments.contains("-OSHIScrollLab") {
            let unread = ProcessInfo.processInfo.arguments.contains("-OSHIScrollUnread")
            for (text, mine, t, isRead, photo) in scrollLabScript(now: now, unread: unread) {
                var m = createDemoConversation(messages: [(text, mine, 0)], myKey: myKey, myAddress: myAddress,
                                               peerKey: aliceKey, peerName: "Alice", now: t)[0]
                m.isRead = isRead
                if let seed = photo {
                    m.plaintextContent = nil
                    m.mediaAttachment = debugPhoto(seed: seed)
                    m.mediaType = .image
                }
                allMessages.append(m)
            }
        } else {
            allMessages.append(contentsOf: createDemoConversation(messages: aliceMessages, myKey: myKey, myAddress: myAddress, peerKey: aliceKey, peerName: "Alice", now: now))
        }
        #else
        allMessages.append(contentsOf: createDemoConversation(messages: aliceMessages, myKey: myKey, myAddress: myAddress, peerKey: aliceKey, peerName: "Alice", now: now))
        #endif

        // Bob conversation — about features
        let bobMessages: [(String, Bool, TimeInterval)] = [
            ("Hey! Did you try the group chat feature yet?", false, -3600),
            ("Not yet, how do I create one?", true, -3300),
            ("Go to the Groups tab and tap + to create a new group. You can add multiple members.", false, -3000),
            ("Cool, I also saw the mesh networking feature. Does it work offline?", true, -2700),
            ("Yes! Bluetooth mesh works even in airplane mode. No internet needed for nearby users.", false, -2400),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: bobMessages, myKey: myKey, myAddress: myAddress, peerKey: bobKey, peerName: "Bob", now: now))

        // Charlie conversation — recent
        let charlieMessages: [(String, Bool, TimeInterval)] = [
            ("Just installed OSHI, this is great!", false, -1800),
            ("Welcome! Try sharing your QR code to add more friends.", true, -1500),
            ("Will do. I love the privacy-first approach!", false, -900),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: charlieMessages, myKey: myKey, myAddress: myAddress, peerKey: charlieKey, peerName: "Charlie", now: now))

        // Dave conversation — media sharing
        let daveMessages: [(String, Bool, TimeInterval)] = [
            ("Hey, can you send photos in OSHI?", false, -5000),
            ("Yes! Tap the + button in any chat to share photos, videos, documents, or contacts.", true, -4700),
            ("Nice, and they're all encrypted too right?", false, -4500),
            ("Absolutely. Media is encrypted before transmission, and only the recipient can decrypt it.", true, -4200),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: daveMessages, myKey: myKey, myAddress: myAddress, peerKey: daveKey, peerName: "Dave", now: now))

        // Eve conversation — security
        let eveMessages: [(String, Bool, TimeInterval)] = [
            ("How secure is this app compared to Signal or WhatsApp?", false, -8000),
            ("OSHI uses Curve25519 for key exchange, AES-256-GCM for encryption, and Ed25519 for signatures. No phone number required.", true, -7800),
            ("I like that there's no phone number needed. True anonymity.", false, -7500),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: eveMessages, myKey: myKey, myAddress: myAddress, peerKey: eveKey, peerName: "Eve", now: now))

        // Frank conversation — offline mesh
        let frankMessages: [(String, Bool, TimeInterval)] = [
            ("The Bluetooth mesh saved us at the festival — zero signal but we still chatted!", false, -6300),
            ("That's the whole point. Messages hop phone-to-phone, no towers needed.", true, -6100),
            ("Genius. Adding my whole hiking group. 🏔️", false, -5900),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: frankMessages, myKey: myKey, myAddress: myAddress, peerKey: frankKey, peerName: "Frank", now: now))

        // Grace conversation — scheduled messages
        let graceMessages: [(String, Bool, TimeInterval)] = [
            ("I scheduled a 'thinking of you' note for my mom's birthday ❤️", false, -5100),
            ("The AI even drafted it for me — totally on-device.", true, -4950),
            ("Best feature. She's going to love it.", false, -4800),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: graceMessages, myKey: myKey, myAddress: myAddress, peerKey: graceKey, peerName: "Grace", now: now))

        // Henry conversation — groups
        let henryMessages: [(String, Bool, TimeInterval)] = [
            ("Found three public groups near me on the discover screen!", false, -2000),
            ("Nice — you can join any of them without sharing your number.", true, -1700),
            ("Already in the Privacy Meetup. See you there 👋", false, -1200),
        ]
        allMessages.append(contentsOf: createDemoConversation(messages: henryMessages, myKey: myKey, myAddress: myAddress, peerKey: henryKey, peerName: "Henry", now: now))

        #if DEBUG
        // __CHAT_OPEN_PERF_2026_11_14__ `-OSHIMediaHeavy`: a long Alice thread
        // full of photos (and the Team Chat below), to look at the open and the
        // scroll of a media-heavy conversation. DEBUG only.
        if ProcessInfo.processInfo.arguments.contains("-OSHIMediaHeavy") {
            let photos = (0..<6).map { Self.debugPhoto(seed: $0) }
            for i in 0..<1_200 {
                let mine = i % 3 == 0
                let t = now.addingTimeInterval(-Double(1_199 - i) * 50 - 30)
                let isPhoto = i % 6 == 0 || i > 1_185
                var m = createDemoConversation(
                    messages: [(isPhoto ? "" : "History \(i): on se retrouve où ?", mine, 0)],
                    myKey: myKey, myAddress: myAddress, peerKey: aliceKey, peerName: "Alice", now: t)[0]
                if isPhoto {
                    m.plaintextContent = nil
                    m.mediaAttachment = photos[i % photos.count]
                    m.mediaType = .image
                }
                allMessages.append(m)
            }
        }
        #endif

        // Save 1-on-1 messages via FileStorage
        FileStorage.shared.save(allMessages, forKey: "messages")
        OshiLog.storage.info("🎭 Created \(allMessages.count) demo messages in \(5) conversations")

        // 4c. Seed nearby "discovered" public groups for the discovery screenshot.
        DiscoveredGroupsManager.shared.discoveredGroups = [
            PublicGroupAd(groupId: UUID(), groupName: "Paris Privacy Meetup", adminPublicKey: aliceKey, memberCount: 214, avatar: nil, timestamp: now),
            PublicGroupAd(groupId: UUID(), groupName: "Offline Travelers", adminPublicKey: bobKey, memberCount: 87, avatar: nil, timestamp: now),
            PublicGroupAd(groupId: UUID(), groupName: "Crypto & Coffee", adminPublicKey: charlieKey, memberCount: 156, avatar: nil, timestamp: now),
            PublicGroupAd(groupId: UUID(), groupName: "Neighborhood Watch", adminPublicKey: daveKey, memberCount: 42, avatar: nil, timestamp: now),
            PublicGroupAd(groupId: UUID(), groupName: "Festival Crew 2026", adminPublicKey: eveKey, memberCount: 309, avatar: nil, timestamp: now),
        ]

        // 5. Create demo groups
        let groupId1 = UUID()
        let groupId2 = UUID()

        let teamGroup = MessageGroup(
            id: groupId1,
            name: "OSHI Team Chat",
            type: .collaborative,
            adminPublicKey: myKey,
            members: [
                GroupMember(publicKey: myKey, alias: nil, joinedAt: now.addingTimeInterval(-86400), isAdmin: true),
                GroupMember(publicKey: aliceKey, alias: "Alice", joinedAt: now.addingTimeInterval(-86400), isAdmin: false),
                GroupMember(publicKey: bobKey, alias: "Bob", joinedAt: now.addingTimeInterval(-86400), isAdmin: false),
                GroupMember(publicKey: charlieKey, alias: "Charlie", joinedAt: now.addingTimeInterval(-82800), isAdmin: false),
            ],
            createdAt: now.addingTimeInterval(-86400),
            lastActivity: now.addingTimeInterval(-300),
            isMuted: false
        )

        let newsGroup = MessageGroup(
            id: groupId2,
            name: "Crypto News",
            type: .publicGroup,
            adminPublicKey: aliceKey,
            members: [
                GroupMember(publicKey: aliceKey, alias: "Alice", joinedAt: now.addingTimeInterval(-172800), isAdmin: true),
                GroupMember(publicKey: myKey, alias: nil, joinedAt: now.addingTimeInterval(-86400), isAdmin: false),
                GroupMember(publicKey: daveKey, alias: "Dave", joinedAt: now.addingTimeInterval(-86400), isAdmin: false),
                GroupMember(publicKey: eveKey, alias: "Eve", joinedAt: now.addingTimeInterval(-43200), isAdmin: false),
            ],
            createdAt: now.addingTimeInterval(-172800),
            lastActivity: now.addingTimeInterval(-600),
            isMuted: false
        )

        // 6. Create group messages — Team Chat
        let teamMsgs: [(String, String, TimeInterval)] = [
            ("Welcome everyone to the OSHI Team Chat!", myKey, -86000),
            ("Great to be here! This group encryption is solid.", aliceKey, -85000),
            ("I love that we can share media too. Photos, videos, documents.", bobKey, -80000),
            ("The mesh networking means we can chat even without internet!", charlieKey, -75000),
            ("Exactly. Bluetooth mesh works even in airplane mode.", myKey, -70000),
            ("Has anyone tried the voice call feature? Crystal clear audio.", aliceKey, -3600),
            ("Yes, the encrypted video call works great too!", bobKey, -1800),
        ]

        var teamGroupMessages: [GroupMessage] = []
        for (i, (text, sender, offset)) in teamMsgs.enumerated() {
            teamGroupMessages.append(GroupMessage(
                id: UUID(), groupId: groupId1, senderPublicKey: sender,
                encryptedContent: Data(text.utf8), timestamp: now.addingTimeInterval(offset),
                isRead: true, messageChainIndex: i, plaintextContent: text
            ))
        }

        // Add bot message
        teamGroupMessages.append(GroupMessage(
            id: UUID(), groupId: groupId1, senderPublicKey: "bot:demo001",
            encryptedContent: Data("Welcome! I'm the group assistant bot. I can provide updates and answer questions.".utf8),
            timestamp: now.addingTimeInterval(-300), isRead: true, messageChainIndex: teamMsgs.count,
            plaintextContent: "[OSHI Bot] Welcome! I'm the group assistant bot. I can provide updates and answer questions."
        ))

        #if DEBUG
        // __GROUP_OPEN_ANCHOR_2026_09_23__ `-OSHILongGroup`: pad the Team Chat with
        // a long, older history (varied bubble heights) so a cold open has to
        // scroll a LazyVStack much taller than the screen — the case where the
        // group used to open parked above the newest message. DEBUG only.
        if ProcessInfo.processInfo.arguments.contains("-OSHILongGroup") {
            let senders = [aliceKey, bobKey, charlieKey, myKey]
            let filler = "The mesh relays this even when the network is down, and the next hop forwards it again. "
            var older: [GroupMessage] = []
            for i in 0..<70 {
                let body = "History #\(i) " + String(repeating: filler, count: i % 4)
                older.append(GroupMessage(
                    id: UUID(), groupId: groupId1, senderPublicKey: senders[i % senders.count],
                    encryptedContent: Data(body.utf8),
                    timestamp: now.addingTimeInterval(-400000 + Double(i) * 4000),
                    isRead: true, messageChainIndex: i, plaintextContent: body))
            }
            teamGroupMessages = older + teamGroupMessages
        }
        // __SCROLL_LAB_2026_11_16__ same script as the Alice thread, in the group.
        if ProcessInfo.processInfo.arguments.contains("-OSHIScrollLab") {
            let unread = ProcessInfo.processInfo.arguments.contains("-OSHIScrollUnread")
            let others = [aliceKey, bobKey, charlieKey]
            teamGroupMessages = scrollLabScript(now: now, unread: unread).enumerated().map { i, row in
                let (text, mine, t, isRead, photo) = row
                let id = UUID()
                let sender = mine ? myKey : others[(i / 3) % others.count]
                if let seed = photo {
                    _ = MessageGroupManager.writeGroupMediaFile(debugPhoto(seed: seed), for: id.uuidString, type: .photo)
                    return GroupMessage(id: id, groupId: groupId1, senderPublicKey: sender, encryptedContent: Data(),
                                        timestamp: t, isRead: isRead, messageChainIndex: i, mediaType: .photo)
                }
                return GroupMessage(id: id, groupId: groupId1, senderPublicKey: sender,
                                    encryptedContent: Data(text.utf8), timestamp: t,
                                    isRead: isRead, messageChainIndex: i, plaintextContent: text)
            }
        }
        // __CHAT_OPEN_PERF_2026_11_14__ see the 1:1 padding above.
        if ProcessInfo.processInfo.arguments.contains("-OSHIMediaHeavy") {
            let senders = [aliceKey, bobKey, charlieKey, myKey]
            var media: [GroupMessage] = []
            for i in 0..<40 {
                let id = UUID()
                _ = MessageGroupManager.writeGroupMediaFile(Self.debugPhoto(seed: i % 6), for: id.uuidString, type: .photo)
                media.append(GroupMessage(
                    id: id, groupId: groupId1, senderPublicKey: senders[i % senders.count],
                    encryptedContent: Data(), timestamp: now.addingTimeInterval(-60_000 + Double(i) * 1_400),
                    isRead: true, messageChainIndex: 100 + i, mediaType: .photo))
            }
            teamGroupMessages = (teamGroupMessages + media).sorted { $0.timestamp < $1.timestamp }
        }
        #endif

        // Create group messages — Crypto News
        let newsMsgs: [(String, String, TimeInterval)] = [
            ("Welcome to Crypto News! Share interesting articles and updates here.", aliceKey, -172000),
            ("Great channel! I'll share some market analysis later.", daveKey, -86000),
            ("Anyone following the latest Ethereum developments?", eveKey, -43200),
            ("Yes! The L2 scaling solutions are getting really impressive.", myKey, -40000),
            ("Agreed, ZK-rollups are the future of scalability.", aliceKey, -38000),
        ]

        var newsGroupMessages: [GroupMessage] = []
        for (i, (text, sender, offset)) in newsMsgs.enumerated() {
            newsGroupMessages.append(GroupMessage(
                id: UUID(), groupId: groupId2, senderPublicKey: sender,
                encryptedContent: Data(text.utf8), timestamp: now.addingTimeInterval(offset),
                isRead: true, messageChainIndex: i, plaintextContent: text
            ))
        }

        // 6b. Three more lightweight groups so the Groups tab fills a large screen.
        func makeGroup(_ id: UUID, _ name: String, _ type: GroupType, _ admin: String,
                       _ memberKeys: [(String, String?)], _ lastOffset: TimeInterval) -> MessageGroup {
            MessageGroup(
                id: id, name: name, type: type, adminPublicKey: admin,
                members: memberKeys.map { GroupMember(publicKey: $0.0, alias: $0.1, joinedAt: now.addingTimeInterval(-86400), isAdmin: $0.0 == admin) },
                createdAt: now.addingTimeInterval(-200000), lastActivity: now.addingTimeInterval(lastOffset), isMuted: false)
        }
        let groupId3 = UUID(), groupId4 = UUID(), groupId5 = UUID()
        let familyGroup = makeGroup(groupId3, "Family ❤️", .adminOnly, myKey,
            [(myKey, nil), (graceKey, "Grace"), (frankKey, "Frank")], -1500)
        let hikersGroup = makeGroup(groupId4, "Weekend Hikers", .collaborative, henryKey,
            [(henryKey, "Henry"), (myKey, nil), (bobKey, "Bob"), (frankKey, "Frank")], -5400)
        let bookGroup = makeGroup(groupId5, "Book Club", .publicGroup, eveKey,
            [(eveKey, "Eve"), (myKey, nil), (graceKey, "Grace"), (charlieKey, "Charlie")], -9000)
        func groupMsg(_ gid: UUID, _ text: String, _ sender: String, _ off: TimeInterval, _ idx: Int) -> GroupMessage {
            GroupMessage(id: UUID(), groupId: gid, senderPublicKey: sender, encryptedContent: Data(text.utf8),
                         timestamp: now.addingTimeInterval(off), isRead: true, messageChainIndex: idx, plaintextContent: text)
        }
        let familyMsgs = [groupMsg(groupId3, "Dinner Sunday at 6? 🍝", graceKey, -3600, 0),
                          groupMsg(groupId3, "Count me in!", myKey, -1500, 1)]
        let hikersMsgs = [groupMsg(groupId4, "Trail conditions look perfect this weekend.", henryKey, -9000, 0),
                          groupMsg(groupId4, "Meet at the trailhead at 8am 🥾", bobKey, -5400, 1)]
        let bookMsgs = [groupMsg(groupId5, "Next read: 'The Age of Surveillance Capitalism'", eveKey, -18000, 0),
                        groupMsg(groupId5, "Fitting choice for this group 😄", charlieKey, -9000, 1)]

        // 7. Save groups and group messages to FileStorage
        FileStorage.shared.save([teamGroup, newsGroup, familyGroup, hikersGroup, bookGroup], forKey: "messageGroups_v2")

        let messagesDict: [String: [GroupMessage]] = [
            groupId1.uuidString: teamGroupMessages,
            groupId2.uuidString: newsGroupMessages,
            groupId3.uuidString: familyMsgs,
            groupId4.uuidString: hikersMsgs,
            groupId5.uuidString: bookMsgs
        ]
        FileStorage.shared.save(messagesDict, forKey: "groupMessages_v2")

        // 8. Update in-memory state of managers
        DispatchQueue.main.async {
            if let msgManager = MessageManager.shared {
                msgManager.messages = allMessages
                msgManager.refreshConversations()
                OshiLog.storage.info("🎭 Updated MessageManager: \(msgManager.messages.count) messages, \(msgManager.conversations.count) conversations")
            }

            var tg = teamGroup; tg.setCurrentUser(myKey)
            var ng = newsGroup; ng.setCurrentUser(myKey)
            var fg = familyGroup; fg.setCurrentUser(myKey)
            var hg = hikersGroup; hg.setCurrentUser(myKey)
            var bg = bookGroup; bg.setCurrentUser(myKey)
            MessageGroupManager.shared.groups = [tg, ng, fg, hg, bg]
            MessageGroupManager.shared.groupMessages = [
                groupId1: teamGroupMessages,
                groupId2: newsGroupMessages,
                groupId3: familyMsgs,
                groupId4: hikersMsgs,
                groupId5: bookMsgs
            ]
            OshiLog.storage.info("🎭 Updated MessageGroupManager: 5 groups")

            // 📸 Seed a couple of group reactions so the screenshot proves the
            // long-press → emoji-reaction feature (broadcast:false = local only).
            if teamGroupMessages.count >= 3 {
                let rm = MessageReactionManager.shared
                rm.addReaction(to: teamGroupMessages[1].id.uuidString, emoji: .thumbsUp, senderPublicKey: bobKey, senderName: "Bob", broadcast: false)
                rm.addReaction(to: teamGroupMessages[1].id.uuidString, emoji: .hundred, senderPublicKey: myKey, senderName: "You", broadcast: false)
                rm.addReaction(to: teamGroupMessages[2].id.uuidString, emoji: .kiss, senderPublicKey: charlieKey, senderName: "Charlie", broadcast: false)
            }
        }

        OshiLog.storage.info("🎭 Demo data loaded successfully!")
    }

    // MARK: - Helper

    #if DEBUG
    /// __SCROLL_LAB_2026_11_16__ The `-OSHIScrollLab` history: 90 rows from three
    /// days ago to two minutes ago (so day separators and times show), with a
    /// tall multi-paragraph bubble every 7th row, a link every 11th, a photo
    /// every 13th. Rows are (text, mine, time, isRead, photoSeed). With
    /// `unread`, the last 40 are incoming and unread; the first is tagged
    /// FIRST-UNREAD. The newest row always ends with LAST-ROW.
    static func scrollLabScript(now: Date, unread: Bool) -> [(String, Bool, Date, Bool, Int?)] {
        // `-OSHIScrollLabCount N` seeds N rows instead of 90 (open-time measurements).
        let args = ProcessInfo.processInfo.arguments
        let count = args.firstIndex(of: "-OSHIScrollLabCount").flatMap { i in
            i + 1 < args.count ? Int(args[i + 1]) : nil
        }.map { max($0, 41) } ?? 90
        let start = Calendar.current.startOfDay(for: now).addingTimeInterval(-3 * 86_400 + 9 * 3_600)
        let span = now.addingTimeInterval(-120).timeIntervalSince(start)
        let para = "On se retrouve devant la gare, puis on marche jusqu'au parc. "
            + "Si le réseau tombe, OSHI passe par le mesh Bluetooth et le message suit quand même. "
        var rows: [(String, Bool, Date, Bool, Int?)] = []
        for i in 0..<count {
            let t = start.addingTimeInterval(span * Double(i) / Double(count - 1))
            let inUnreadTail = unread && i >= count - 40
            let mine = inUnreadTail ? false : (i / 2) % 2 == 0
            var text: String
            if i % 7 == 3 {
                text = "Long #\(i)\n" + (1...4).map { "Paragraphe \($0). " + String(repeating: para, count: 2) }
                    .joined(separator: "\n\n")
            } else if i % 11 == 5 {
                text = "Link #\(i): https://oshi-messenger.com/faq — and https://example.org/a/very/long/path/that/wraps?query=1"
            } else {
                text = "Message #\(i) " + ["ok 👍", "on part à quelle heure ?", "j'arrive dans 10 min, garde-moi une place",
                                           "Tu as vu le plan ? Il faut prendre la deuxième rue à gauche après le pont."][i % 4]
            }
            if inUnreadTail && i == count - 40 { text = "FIRST-UNREAD " + text }
            if i == count - 1 { text += "\nLAST-ROW" }
            let photo: Int? = (i % 13 == 8 && i != count - 1 && !(inUnreadTail && i == count - 40)) ? i % 6 : nil
            rows.append((text, mine, t, !inUnreadTail, photo))
        }
        return rows
    }

    /// A photo-like JPEG for `-OSHIMediaHeavy`.
    static func debugPhoto(seed: Int) -> Data {
        let size = CGSize(width: 1200, height: 900)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let hues: [CGFloat] = [0.0, 0.12, 0.3, 0.55, 0.7, 0.85]
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor(hue: hues[seed % hues.count], saturation: 0.6, brightness: 0.8, alpha: 1).setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            for k in 0..<120 {
                UIColor(hue: CGFloat((k * 37 + seed * 11) % 100) / 100, saturation: 0.7, brightness: 0.9, alpha: 0.8).setFill()
                ctx.fill(CGRect(x: (k * 97) % 1100, y: (k * 53 + seed * 31) % 800, width: 40 + (k % 7) * 20, height: 30 + (k % 5) * 25))
            }
            ("Photo \(seed)" as NSString).draw(at: CGPoint(x: 40, y: 40),
                withAttributes: [.font: UIFont.boldSystemFont(ofSize: 120), .foregroundColor: UIColor.white])
        }.jpegData(compressionQuality: 0.8) ?? Data()
    }
    #endif

    private static func createDemoConversation(
        messages: [(String, Bool, TimeInterval)],
        myKey: String, myAddress: String,
        peerKey: String, peerName: String,
        now: Date
    ) -> [SecureMessage] {
        messages.map { (text, isMine, offset) in
            SecureMessage(
                id: UUID().uuidString,
                senderAddress: isMine ? String(myAddress.prefix(10)) : peerName,
                recipientAddress: isMine ? peerName : String(myAddress.prefix(10)),
                encryptedContent: EncryptedMessage(
                    ciphertext: Data(text.utf8).base64EncodedString(),
                    signature: "demo_signature",
                    senderPublicKey: isMine ? myKey : peerKey,
                    timestamp: now.addingTimeInterval(offset)
                ),
                timestamp: now.addingTimeInterval(offset),
                isRead: true,
                deliveryStatus: .delivered,
                senderPublicKey: isMine ? myKey : peerKey,
                recipientPublicKey: isMine ? peerKey : myKey,
                plaintextContent: text,
                deliveryMethod: .ipfs
            )
        }
    }
}
