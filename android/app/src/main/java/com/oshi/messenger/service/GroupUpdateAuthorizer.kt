package com.oshi.messenger.service

import com.oshi.messenger.data.model.GroupType

/**
 * What an inbound group update is allowed to touch.
 * iOS `GroupUpdateDecision` (GroupMessaging.swift:295-302).
 */
data class GroupUpdateDecision(
    val apply: Boolean,
    val keepAdminSet: Boolean,
    val keepMembership: Boolean,
    val keepName: Boolean,
    /** Short machine-readable tag, logged and asserted in tests. */
    val reason: String
)

/**
 * Authorization for every inbound `📢GROUP_UPDATE📢`.
 *
 * Port of iOS `MessageGroupManager.authorizeGroupUpdate` / `applyGroupUpdate`
 * (GroupMessaging.swift:862-931, called at :946-957). Before this existed on Android,
 * `applyiOSGroupUpdateBroadcast` wrote `name`, `memberPublicKeys` and `adminPublicKeys`
 * unconditionally and did not even take a sender — so anyone who could deliver one payload
 * could rename a group, kick every member, or make themselves admin on every Android device.
 *
 * Three rules, in the order they are cheapest to check:
 *
 *   1. VERSION — a definition older than ours is a replay; ignore it. Only compared when both
 *      sides carry a version. Android does not persist a `stateVersion` yet (audit G-10), so
 *      today the current version is always null and this check is inert; it is here so that
 *      wiring G-10 arms replay protection without touching the authorizer.
 *   2. STANDING — an update from someone who is not currently a member of our copy is ignored.
 *   3. AUTHORITY — the admin set is only ever taken from a sender who is ALREADY an admin here.
 *      Membership and name follow the group's own policy: any member may in a collaborative or
 *      public group, admins only in an admin-only group.
 *
 * A failed check downgrades what the update may touch — it does not throw the whole thing away.
 * Cosmetic fields (picture, wallpaper, pin) legitimately arrive from ordinary members, and a hard
 * reject would trade a privilege-escalation hole for a sync outage.
 *
 * `sender == null` is the legacy-transport case: the VPS/IPFS route
 * (`VPSClient.onGroupUpdateBroadcast` → `MessageRepository:1059`) carries no sender at all. It
 * keeps today's permissive behaviour for everything EXCEPT the admin set, which is never taken
 * from an unauthenticated update — exactly iOS at GroupMessaging.swift:872-875.
 *
 * Pure: no Android APIs, no state. JVM-unit-testable (`GroupUpdateAuthorizationTest`).
 */
object GroupUpdateAuthorizer {

    /**
     * Canonical form for key comparison — base64url → base64, padding dropped.
     * iOS `MessageManager.canonicalIdentity` (MessageManager.swift:6246). Reuses the one
     * Android already has so a key does not compare equal in calls and unequal in groups.
     */
    fun canonicalIdentity(key: String): String = CallAdmissionPolicy.normalizeKey(key)

    /**
     * May we answer a `member_sync_request`, and may we add the requester?
     *
     * __MEMBER_SYNC_AUTHZ_2026_11_02__ Found auditing OSHI against the 2025–26
     * messenger CVEs, in the class the NCSC describes as "fraudulent
     * integration into groups".
     *
     * What the Android handler did before this: it took `requesterPublicKey`
     * **from the message body**, fell back to the sender, and if that key was
     * not in the group it ADDED it and replied with the full roster. No check
     * that the sender was a member, none that the key was the sender's, none
     * that they had not been removed, and no permission check — while iOS's
     * twin (`GroupMessaging.swift:1336-1371`) made all four.
     *
     * So on Android a contact who learned a group id could put ANY public key
     * into your copy of that group's membership, and read back the roster. The
     * ratchet was never the problem: the message was authenticated and the
     * authorisation was missing — which is the whole lesson of the 2026 Signal
     * injection paper, in our own code.
     *
     * The four rules, in the order they decide:
     *  1. the sender must be known (an unauthenticated transport has no say);
     *  2. the body may not name someone else — a request speaks for ITS sender;
     *  3. a member we evicted may not re-add themselves;
     *  4. a non-member may be added only if THIS device is allowed to add
     *     members in this group; otherwise the request is simply ignored.
     */
    data class SyncRequestDecision(
        val answer: Boolean,
        val addRequester: Boolean,
        val reason: String,
    )

    fun authorizeMemberSyncRequest(
        sender: String?,
        requesterFromBody: String?,
        currentMemberKeys: List<String>,
        evictedMemberKeys: List<String>,
        weMayAddMembers: Boolean,
    ): SyncRequestDecision {
        val s = sender?.takeIf { it.isNotBlank() }?.let { canonicalIdentity(it) }
            ?: return SyncRequestDecision(false, false, "unauthenticated")
        val body = requesterFromBody?.takeIf { it.isNotBlank() }?.let { canonicalIdentity(it) }
        if (body != null && body != s) {
            return SyncRequestDecision(false, false, "body-names-a-third-party")
        }
        if (evictedMemberKeys.any { canonicalIdentity(it) == s }) {
            return SyncRequestDecision(false, false, "evicted")
        }
        val isMember = currentMemberKeys.any { canonicalIdentity(it) == s }
        if (isMember) return SyncRequestDecision(true, false, "member")
        if (!weMayAddMembers) return SyncRequestDecision(false, false, "not-a-member-and-we-may-not-add")
        // v1 invite completion: the requester holds an invite we issued, and this
        // device is allowed to add. Adding broadcasts the new definition to the
        // whole roster, so the rest of the group learns about them too.
        return SyncRequestDecision(true, true, "added-by-invite")
    }

    /**
     * __GROUP_SETTINGS_2026_11_20__ Link-join rules on top of [authorizeMemberSyncRequest]
     * (docs/GROUP_INFO_SETTINGS_SPEC.md §3.3), for a request from a NON-member that the
     * legacy rules would accept. Once a group has an invite token (an admin reset its link),
     * a `member_sync_request` must carry it; a dissolved group admits nobody.
     */
    fun authorizeInviteToken(
        base: SyncRequestDecision,
        settings: com.oshi.messenger.data.model.GroupSettings?,
        requestToken: String?,
    ): SyncRequestDecision {
        if (!base.addRequester) return base
        if (settings?.dissolved == true) return SyncRequestDecision(false, false, "dissolved")
        val expected = settings?.inviteToken ?: return base
        val given = requestToken.orEmpty()
        val ok = java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8)
        )
        return if (ok) SyncRequestDecision(true, true, "added-by-invite-token")
        else SyncRequestDecision(false, false, "invite-revoked")
    }

    data class JoinDecision(val accept: Boolean, val alreadyMember: Boolean, val reason: String)

    /**
     * `join_request {handle}` from a public link (spec §3.1/§3.3). Only an ADMIN device of a
     * group that is public under exactly that handle admits anyone; everything else is refused
     * silently. [recentAccepts] = joins this device accepted for the group in the last hour.
     */
    fun authorizeJoinRequest(
        sender: String?,
        requesterFromBody: String?,
        requestHandle: String?,
        settings: com.oshi.messenger.data.model.GroupSettings?,
        currentMemberKeys: List<String>,
        evictedMemberKeys: List<String>,
        weAreAdmin: Boolean,
        recentAccepts: Int,
        maxAcceptsPerHour: Int = 60,
    ): JoinDecision {
        val s = sender?.takeIf { it.isNotBlank() }?.let { canonicalIdentity(it) }
            ?: return JoinDecision(false, false, "unauthenticated")
        val body = requesterFromBody?.takeIf { it.isNotBlank() }?.let { canonicalIdentity(it) }
        if (body != null && body != s) return JoinDecision(false, false, "body-names-a-third-party")
        val h = com.oshi.messenger.data.model.GroupSettings.normalizeHandle(requestHandle)
        if (h == null || settings == null || !settings.isPublic || settings.handle != h) {
            return JoinDecision(false, false, "unknown-handle")
        }
        if (!weAreAdmin) return JoinDecision(false, false, "not-admin")
        if (settings.dissolved) return JoinDecision(false, false, "dissolved")
        if (evictedMemberKeys.any { canonicalIdentity(it) == s }) return JoinDecision(false, false, "banned")
        if (currentMemberKeys.any { canonicalIdentity(it) == s }) return JoinDecision(true, true, "member")
        if (recentAccepts >= maxAcceptsPerHour) return JoinDecision(false, false, "rate-limited")
        return JoinDecision(true, false, "accepted")
    }

    /** iOS `authorizeGroupUpdate` (GroupMessaging.swift:863-905). */
    fun authorize(
        currentMemberKeys: List<String>,
        currentAdminKeys: List<String>,
        currentType: GroupType,
        currentStateVersion: Int? = null,
        incomingStateVersion: Int? = null,
        sender: String?
    ): GroupUpdateDecision {
        // 1. Replay of an older definition.
        if (currentStateVersion != null && incomingStateVersion != null &&
            incomingStateVersion < currentStateVersion) {
            return GroupUpdateDecision(
                apply = false, keepAdminSet = true, keepMembership = true, keepName = true,
                reason = "stale-version"
            )
        }

        // 🔒 __GROUP_UNAUTH_TRANSPORT_2026_11_20__ No sender = nobody vouched for this
        // payload (IPFS/VPS `group_update`, whose queue POST is unauthenticated and whose
        // groupIds sit in public pins). It used to REPLACE the roster and the name: anyone
        // on the internet holding a groupId could add their own key — and this phone would
        // then wrap every later group message to them — or kick everyone. Cosmetic fields
        // (picture, wallpaper, pin) still apply; roster, name and admins never do.
        if (sender.isNullOrBlank()) {
            return GroupUpdateDecision(
                apply = true, keepAdminSet = true, keepMembership = true, keepName = true,
                reason = "unauthenticated-transport"
            )
        }

        val senderKey = canonicalIdentity(sender)

        // 2. Not one of us.
        if (currentMemberKeys.none { canonicalIdentity(it) == senderKey }) {
            return GroupUpdateDecision(
                apply = false, keepAdminSet = true, keepMembership = true, keepName = true,
                reason = "sender-not-a-member"
            )
        }

        // 3. Authority.
        if (currentAdminKeys.any { canonicalIdentity(it) == senderKey }) {
            return GroupUpdateDecision(
                apply = true, keepAdminSet = false, keepMembership = false, keepName = false,
                reason = "admin"
            )
        }
        return when (currentType) {
            GroupType.COLLABORATIVE, GroupType.PUBLIC ->
                // The group's own policy already lets any member add or rename
                // (`Group.canAddMembers`); only the admin set stays out of reach.
                GroupUpdateDecision(
                    apply = true, keepAdminSet = true, keepMembership = false, keepName = false,
                    reason = "member-of-open-group"
                )
            GroupType.ADMIN_ONLY ->
                GroupUpdateDecision(
                    apply = true, keepAdminSet = true, keepMembership = true, keepName = true,
                    reason = "member-of-admin-only-group"
                )
        }
    }

    /**
     * 🔒 __GROUP_TOMBSTONE_AUTHZ_2026_11_20__ Eviction tombstones are MEMBERSHIP: they
     * remove people. They are merged only when [decision] lets the sender change the
     * roster. Before, the union ran for every applied update, so an ordinary member of an
     * admin-only group (or an unauthenticated IPFS payload) could tombstone everyone —
     * admins included — and the tombstones stuck.
     */
    fun mergeEvictedIfAuthorized(
        decision: GroupUpdateDecision,
        current: List<String>,
        incoming: List<String>?,
        /** Current admins: a non-admin may not tombstone them (__GROUP_TYPE_TAKEOVER_2026_11_20__). */
        currentAdmins: List<String> = emptyList(),
    ): List<String> {
        if (!decision.apply || decision.keepMembership) return current
        val allowed = if (decision.keepAdminSet) {
            val admins = currentAdmins.map { canonicalIdentity(it) }.toSet()
            incoming?.filter { canonicalIdentity(it) !in admins }
        } else incoming
        return unionEvicted(current, allowed)
    }

    /**
     * 🔒 __GROUP_TYPE_TAKEOVER_2026_11_20__ A member who may change the roster but is not an
     * admin (open group) may not REMOVE an admin: every current admin who is a member stays.
     */
    fun keepAdminsInRoster(
        decision: GroupUpdateDecision,
        incomingMembers: List<String>,
        currentMembers: List<String>,
        currentAdmins: List<String>,
    ): List<String> {
        if (!decision.keepAdminSet) return incomingMembers
        val present = incomingMembers.map { canonicalIdentity(it) }.toSet()
        val memberSet = currentMembers.map { canonicalIdentity(it) }.toSet()
        val missingAdmins = currentAdmins.filter { canonicalIdentity(it) in memberSet && canonicalIdentity(it) !in present }
        return incomingMembers + missingAdmins
    }

    /**
     * 🔒 __GROUP_TOMBSTONE_AUTHZ_2026_11_20__ A definition's `stateVersion` is adopted only
     * from a sender whose update could change the roster. Otherwise any member could push
     * the counter to 2^31 and every later real admin update would be refused as stale.
     */
    fun mayAdoptStateVersion(decision: GroupUpdateDecision): Boolean =
        decision.apply && !decision.keepMembership

    /**
     * 🔒 __GROUP_TYPE_TAKEOVER_2026_11_20__ The group TYPE is the permission model itself
     * (admin_only / collaborative / public). Taking it from a non-admin let an ordinary
     * member of an admin-only group send `type: collaborative` and gain add/remove/rename
     * rights over everyone. Only an admin (the decision that may take the admin set) may
     * change it.
     */
    fun mayChangeGroupType(decision: GroupUpdateDecision): Boolean = decision.apply && !decision.keepAdminSet

    /**
     * 🔒 __GROUP_TYPE_TAKEOVER_2026_11_20__ Description, picture, wallpaper, pin, avatar,
     * isPublic: whoever may rename may change these (admin, or any member of an open
     * group). A plain member of an admin-only group and an unauthenticated payload may not.
     */
    fun mayChangeGroupSettings(decision: GroupUpdateDecision): Boolean = decision.apply && !decision.keepName

    /** Largest forward jump of `stateVersion` accepted in one update (desktop be19d428). */
    const val MAX_STATE_VERSION_STEP = 1024

    /**
     * 🔒 __GROUP_TYPE_TAKEOVER_2026_11_20__ Even from an admin, a version far ahead of ours is
     * not adopted: one update cannot push the counter so high that every later legitimate
     * update is refused as stale.
     */
    fun isPlausibleVersionStep(current: Int?, incoming: Int): Boolean =
        incoming >= 0 && incoming.toLong() - (current ?: 0).toLong() <= MAX_STATE_VERSION_STEP

    /**
     * The admin half of iOS `applyGroupUpdate` (GroupMessaging.swift:911-921): when the decision
     * protects the admin set, re-stamp it from OUR copy keyed by member, so a member the incoming
     * update introduces cannot arrive pre-promoted.
     *
     * [mergedMemberKeys] empty means the update carried no roster, so there is nothing to key on
     * and the local admin set stands unchanged.
     */
    fun restampAdminSet(currentAdminKeys: List<String>, mergedMemberKeys: List<String>): List<String> {
        if (mergedMemberKeys.isEmpty()) return currentAdminKeys
        val admins = currentAdminKeys.map { canonicalIdentity(it) }.toSet()
        return mergedMemberKeys.filter { canonicalIdentity(it) in admins }
    }

    /**
     * iOS's ingest guard for an UNKNOWN group (GroupMessaging.swift:1044-1048): never materialise
     * a group whose roster does not contain us. Without it, any broadcast — including the one a
     * peer sends back after we left — re-creates the group we just walked out of (audit G-3).
     */
    fun isSelfInRoster(myPublicKey: String, memberKeys: List<String>): Boolean {
        if (myPublicKey.isBlank()) return false
        val mine = canonicalIdentity(myPublicKey)
        return memberKeys.any { canonicalIdentity(it) == mine }
    }

    /**
     * __EVICTION_TOMBSTONE_WAS_DEVICE_LOCAL_2026_11_04__ Merge our eviction tombstones
     * with the ones an incoming definition carries.
     *
     * UNION, never the incoming value alone, and never a subtraction. A definition
     * written before an eviction still has the evicted key in its roster and nothing in
     * its tombstone list; honouring it as written is how a member we removed reappeared.
     * The same asymmetry protects us from a hostile sender, who can only ever ADD to the
     * list, never clear it. The one way a key leaves is a deliberate re-add on this
     * device ([com.oshi.messenger.service.GroupManager.addMember]), so an eviction is
     * never a ban an admin cannot undo.
     *
     * iOS `applyGroupUpdate` (`GroupMessaging.swift:1091-1102`).
     */
    fun unionEvicted(ours: List<String>, incoming: List<String>?): List<String> =
        (ours + (incoming ?: emptyList())).distinctBy { canonicalIdentity(it) }

    /**
     * Drop every evicted key from a roster we are about to accept.
     *
     * Separate from [unionEvicted] because the tombstone list and the roster are written
     * at different moments: the union has to happen first, so that a key this very
     * definition tells us about is already excluded by the time the roster is filtered.
     *
     * iOS `applyGroupUpdate` (`GroupMessaging.swift:1103-1108`).
     */
    fun withoutEvicted(memberKeys: List<String>, evictedKeys: List<String>): List<String> {
        if (evictedKeys.isEmpty()) return memberKeys
        val evicted = evictedKeys.map { canonicalIdentity(it) }.toSet()
        return memberKeys.filter { canonicalIdentity(it) !in evicted }
    }
}
