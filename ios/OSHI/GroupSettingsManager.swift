//
//  GroupSettingsManager.swift
//  OSHI
//
//  __GROUP_INFO_SETTINGS_2026_11_20__ The actions behind the group info page
//  (GROUP_INFO_SETTINGS_SPEC §2, §3, §4.3). Every change an admin makes is a
//  new definition through `broadcastFullGroupUpdate` — the same E2E path as a
//  rename — and every receive goes through the authorizer in GroupMessaging.
//  The decisions themselves are the pure functions in GroupSettings.swift.
//

import Foundation

extension MessageGroupManager {

    // MARK: - Helpers

    func isCurrentUserAdmin(of groupId: UUID) -> Bool {
        guard let g = groups.first(where: { $0.id == groupId }) else { return false }
        return GroupSettingsPolicy.isAdmin(currentUserPublicKey, in: g)
    }

    /// A local informational row (welcome message, dissolved). Never sent.
    func addLocalInfoRow(to groupId: UUID, text: String, atTop: Bool = false) {
        let row = GroupMessage(
            id: UUID(),
            groupId: groupId,
            senderPublicKey: "SYSTEM",
            encryptedContent: Data(),
            timestamp: atTop ? (groupMessages[groupId]?.map(\.timestamp).min()?.addingTimeInterval(-1) ?? Date()) : Date(),
            isRead: true,
            messageChainIndex: 0,
            plaintextContent: text,
            systemMessageType: .groupInfo,
            systemMessageData: GroupSystemMessageData(actorPublicKey: "SYSTEM", targetPublicKey: nil)
        )
        var list = groupMessages[groupId] ?? []
        if atTop { list.insert(row, at: 0) } else { list.append(row) }
        groupMessages[groupId] = list
        saveGroupMessagesPublic()
        objectWillChange.send()
        NotificationCenter.default.post(name: NSNotification.Name("NewGroupMessage"), object: row)
    }

    /// §2.5: a group this device just materialised (joined). One welcome row per
    /// group id per device.
    func showWelcomeIfNeeded(_ group: MessageGroup) {
        guard let w = group.settings?.welcome, !w.isEmpty,
              GroupLocalFlags.shared.markWelcomeShown(group.id) else { return }
        addLocalInfoRow(to: group.id, text: String(format: "gs_welcome_row".localized, w), atTop: true)
    }

    /// §2.8: diff and append. `actor` = authenticated sender, or us for local actions.
    func recordAdminLog(old: MessageGroup, new: MessageGroup, actor: String?) {
        guard let actor, !actor.isEmpty else { return }
        GroupAdminLogStore.shared.append(new.id, GroupAdminLog.diff(old: old, new: new, actor: actor))
    }

    /// Store a locally edited copy, log it, and send the definition (stateVersion+1).
    @discardableResult
    private func commitLocalEdit(_ groupId: UUID, _ edit: (inout MessageGroup) -> Void) -> MessageGroup? {
        guard let i = groups.firstIndex(where: { $0.id == groupId }) else { return nil }
        let old = groups[i]
        var g = old
        edit(&g)
        guard g.settings != old.settings || g.groupDescription != old.groupDescription || g.type != old.type else { return old }
        g.lastActivity = Date()
        groups[i] = g
        saveGroups()
        recordAdminLog(old: old, new: g, actor: currentUserPublicKey)
        broadcastFullGroupUpdate(group: groups[i], notifyMembers: false)
        NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil,
                                        userInfo: ["groupId": groupId.uuidString])
        return groups[i]
    }

    // MARK: - Admin edits (§2)

    /// Any admin-only subkey (§2.1). Refused locally for a non-admin — the
    /// receivers would ignore it anyway (settings-not-admin).
    @discardableResult
    func updateGroupSettings(_ groupId: UUID, _ mutate: (inout GroupSettings) -> Void) -> Bool {
        guard isCurrentUserAdmin(of: groupId) else { return false }
        return commitLocalEdit(groupId) { g in
            var s = g.settings ?? GroupSettings()
            mutate(&s)
            s.welcome = GroupSettings.truncateUTF16(s.welcome, max: GroupSettings.maxWelcome)
            s.allowedReactions = Array(s.allowedReactions.prefix(GroupSettings.maxAllowedReactions))
            g.settings = s
        } != nil
    }

    /// §2.2: shipped rule (whoever may rename). `""` once set = cleared, and is sent.
    @discardableResult
    func updateGroupDescription(_ groupId: UUID, text: String) -> Bool {
        guard var g = groups.first(where: { $0.id == groupId }) else { return false }
        if let me = currentUserPublicKey { g.setCurrentUser(me) }
        guard g.canChangeName else { return false }
        let t = GroupSettings.truncateUTF16(text.trimmingCharacters(in: .whitespacesAndNewlines),
                                            max: GroupSettings.maxDescription)
        return commitLocalEdit(groupId) { g in
            if g.groupDescription == nil && t.isEmpty { return }
            g.groupDescription = t
        } != nil
    }

    /// §5 item 4 — the existing permission model; admin only (receive already enforces).
    @discardableResult
    func setGroupType(_ groupId: UUID, _ type: GroupType) -> Bool {
        guard isCurrentUserAdmin(of: groupId) else { return false }
        return commitLocalEdit(groupId) { $0.type = type } != nil
    }

    /// §3.2 "Reset link": a new token; links without it stop working on new-build admins.
    @discardableResult
    func resetInviteLink(_ groupId: UUID) -> Bool {
        updateGroupSettings(groupId) { $0.inviteToken = GroupSettings.newInviteToken() }
    }

    /// §4.3: PUT first, broadcast only what the server accepted. `handle == nil` = Private.
    func setVisibility(_ groupId: UUID, handle: String?, showPreview: Bool) async -> GroupHandleRegistry.PutResult {
        guard isCurrentUserAdmin(of: groupId), let g = groups.first(where: { $0.id == groupId }) else { return .error }
        let old = g.settings ?? GroupSettings()
        guard let raw = handle else {
            if let h = old.handle, old.visibility == .public { await GroupHandleRegistry.unregister(h) }
            // __CHANNEL_WEB_2026_11_26__ releasing the handle purges the web copy server-side
            // (spec §7.1); the switch goes off with it.
            updateGroupSettings(groupId) { $0.visibility = .private; $0.showPreview = false; $0.webPreview = false }
            return .ok
        }
        guard let h = GroupSettings.normalizedHandle(raw) else { return .invalid }
        var next = old
        next.visibility = .public
        next.handle = h
        next.showPreview = showPreview
        let r = await GroupHandleRegistry.register(h, group: g, settings: next)
        guard r == .ok else { return r }
        if let previous = old.handle, previous != h, old.visibility == .public {
            await GroupHandleRegistry.unregister(previous)
        }
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "oshi.groupHandle.lastPut.\(h)")
        updateGroupSettings(groupId) { $0 = next }
        return .ok
    }

    /// §4.3 "Re-PUT whenever the admin set changes".
    func refreshHandleRegistration(_ groupId: UUID) {
        guard let g = groups.first(where: { $0.id == groupId }), let s = g.settings, s.isPublic, let h = s.handle,
              GroupSettingsPolicy.isAdmin(currentUserPublicKey, in: g) else { return }
        Task { _ = await GroupHandleRegistry.register(h, group: g, settings: s) }
    }

    /// §2.7 Unban: THIS device's tombstone only (tombstones are a union by design).
    func unbanMember(_ groupId: UUID, publicKey: String) {
        guard isCurrentUserAdmin(of: groupId), let i = groups.firstIndex(where: { $0.id == groupId }) else { return }
        let k = MessageManager.canonicalIdentity(publicKey)
        var ev = groups[i].evictedMemberKeys ?? []
        ev.removeAll { MessageManager.canonicalIdentity($0) == k }
        groups[i].evictedMemberKeys = ev.isEmpty ? nil : ev
        saveGroups()
        objectWillChange.send()
    }

    /// §2.9 "Delete for everyone".
    func dissolveGroupForEveryone(_ groupId: UUID) {
        guard isCurrentUserAdmin(of: groupId), let g = groups.first(where: { $0.id == groupId }) else { return }
        let handle = (g.settings?.visibility == .public) ? g.settings?.handle : nil
        updateGroupSettings(groupId) { $0.dissolved = true }
        if let framed = GroupWireV2.framedCompanion(["type": "deleted", "groupId": groupId.uuidString]) {
            let to = g.members.map(\.publicKey)
            Task { _ = await MessageManager.shared?.v2SendGroupState(framed, to: to, wake: false) }
        }
        Task {
            if let handle { await GroupHandleRegistry.unregister(handle) }
        }
        GroupAdminLogStore.shared.forget(groupId)
        GroupLocalFlags.shared.forgetGroup(groupId)
        deleteGroup(groupId)
        NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil,
                                        userInfo: ["groupId": groupId.uuidString, "deleted": true])
    }

    // MARK: - Receive side

    /// `{"type":"deleted","groupId"}` (Android companion of a dissolve). From an
    /// admin of OUR copy on the authenticated channel only: marks the group
    /// dissolved (read-only), never deletes the person's history.
    func handleGroupDeletedCompanion(groupId: UUID, sender: String?, authenticated: Bool) {
        guard authenticated, let i = groups.firstIndex(where: { $0.id == groupId }),
              GroupSettingsPolicy.isAdmin(sender, in: groups[i]) else {
            OshiLog.messaging.info("🛑 'deleted' companion ignored (not an admin on the authenticated channel)")
            return
        }
        markDissolved(at: i, actor: sender)
    }

    func markDissolved(at i: Int, actor: String?) {
        guard groups[i].settings?.dissolved != true else { return }
        let old = groups[i]
        var s = groups[i].settings ?? GroupSettings()
        s.dissolved = true
        groups[i].settings = s
        saveGroups()
        recordAdminLog(old: old, new: groups[i], actor: actor)
        addLocalInfoRow(to: groups[i].id, text: "gs_dissolved_banner".localized)
        NotificationCenter.default.post(name: NSNotification.Name("GroupUpdated"), object: nil,
                                        userInfo: ["groupId": groups[i].id.uuidString])
    }

    /// §3.3 `join_request` (by handle) — the admin-device side.
    func handleJoinRequest(_ json: [String: Any], sender: String?, authenticated: Bool) {
        guard authenticated else {
            OshiLog.messaging.info("⏭️ join_request ignored (not on the authenticated v2 channel)")
            return
        }
        let (verdict, gid) = GroupJoinAuthorizer.authorizeHandleJoin(
            sender: sender, requester: json["requesterPublicKey"] as? String,
            handle: json["handle"] as? String, groups: groups, me: currentUserPublicKey,
            acceptsInLastHour: { GroupJoinRateLimiter.shared.count($0) })
        guard let sender, let gid else {
            OshiLog.messaging.info("🛑 join_request refused (\(String(describing: verdict)))")
            return
        }
        switch verdict {
        case .accept:
            GroupJoinRateLimiter.shared.record(gid)
            addMember(to: gid, publicKey: sender, alias: nil)
            GroupAdminLogStore.shared.append(gid, [GroupAdminEvent(at: Date(), actor: sender, kind: "joined_via_link", detail: nil)])
            OshiLog.messaging.info("✅ join_request accepted for \(sender.prefix(8))…")
        case .alreadyMember:
            if let g = groups.first(where: { $0.id == gid }) {
                broadcastFullGroupUpdate(group: g, notifyMembers: false, bumpVersion: false, onlyTo: [sender])
            }
        case .refuse(let why):
            OshiLog.messaging.info("🛑 join_request refused (\(why))")
        }
    }

    // MARK: - Joiner side (§3.1)

    /// Step 4: one `join_request` to each `joinKeys` entry (≤ 8). No stub group.
    func sendPublicJoinRequest(handle: String, joinKeys: [String]) async -> Bool {
        guard let me = currentUserPublicKey, !me.isEmpty,
              let h = GroupSettings.normalizedHandle(handle),
              let framed = GroupWireV2.framedCompanion(["type": "join_request", "handle": h, "requesterPublicKey": me])
        else { return false }
        let keys = Array(joinKeys.prefix(8)).filter { MessageManager.canonicalIdentity($0) != MessageManager.canonicalIdentity(me) }
        guard !keys.isEmpty else { return false }
        let reached = await MessageManager.shared?.v2SendGroupState(framed, to: keys) ?? []
        return !reached.isEmpty
    }
}
