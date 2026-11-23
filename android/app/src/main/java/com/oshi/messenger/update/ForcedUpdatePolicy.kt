package com.oshi.messenger.update

import com.oshi.messenger.network.version.AppVersions

/**
 * __FORCED_UPDATE_ANDROID_2026_10_13__
 *
 * What the app is allowed to do about a build that is too old, as a pure
 * function of four facts. No Android types, no network, no Play — so every
 * branch below is a JVM unit test rather than something you discover on a
 * stranger's phone.
 *
 * ### Why a THIRD floor instead of reusing the one that already exists
 *
 * `MinimumVersionGate.minimumVersion` (`android_min_supported_version`) has
 * shipped in every build since 2026-09-09 and draws a **dismissible** banner.
 * That key is therefore already live in the field on builds that contain none
 * of this file, and whatever it is set to takes effect on them immediately.
 *
 * If blocking read the same key, then setting a floor to nag the 1.6.36 users
 * would ALSO, on every build carrying this code, turn into a hard block — one
 * decision silently doing two different things to two populations. So blocking
 * has its own key, [BLOCKING_KEY], and it defaults to absent. Setting the nag
 * floor can never lock anyone out; locking people out has to be asked for.
 *
 * ### Why "forced" is never allowed to mean "stuck"
 *
 * The owner's standing contract on version gates: *fail open when the server is
 * unreachable, and never block offline messaging.* A hard block that trips on a
 * missing answer would brick an app whose entire selling point is working when
 * the network does not. Hence [decide] returns [Decision.BLOCK] only when all
 * of these are true at once:
 *
 *  * the server **affirmatively** published a blocking floor — not a timeout, not
 *    a 500, not a malformed body, all of which arrive here as `null`; and
 *  * this build is **provably** below it, by integer comparison per component
 *    (`AppVersions`, because `"1.0.9" > "1.0.43"` as strings); and
 *  * Play has an update **actually ready to install** for this device.
 *
 * That last condition is the one that keeps the block honest. Play is what
 * carries out the update, so if Play has nothing to offer — no Play Services at
 * all, a sideloaded APK, a device whose region has not received the release yet,
 * a Honor phone with no GMS — then blocking would demand of the user something
 * they have no way to do. In every one of those cases this returns [Decision.OFFER]
 * at most, which is the dismissible banner that already exists.
 */
object ForcedUpdatePolicy {

    /** The blocking floor. Deliberately NOT the key the dismissible banner reads. */
    const val BLOCKING_KEY = "android_blocking_update_version"

    enum class Decision {
        /** Draw nothing. */
        NONE,

        /** The existing dismissible banner, with a link to the store. */
        OFFER,

        /**
         * Play's own full-screen IMMEDIATE flow: it downloads, installs and
         * restarts the app, and the user cannot compose past it.
         *
         * Google draws and localises this screen, which is why forcing adds no
         * strings to 36 locales and cannot ship a half-translated wall.
         */
        BLOCK,
    }

    /**
     * How Play answered `getAppUpdateInfo`, reduced to the only three states
     * that change the decision. Anything else — an exception, a missing Play
     * Store, `UNKNOWN`, a failed task — is [NOT_AVAILABLE], the safe value.
     */
    enum class PlayState {
        /** Play cannot be asked, or has nothing for us. */
        NOT_AVAILABLE,

        /** An update exists and Play reports it installable. */
        AVAILABLE,

        /**
         * An IMMEDIATE update was already started and the process died or the
         * user backgrounded it. Google requires the host app to resume this in
         * `onResume`, or the user is left looking at a half-updated app.
         */
        IN_PROGRESS,
    }

    /**
     * The decision.
     *
     * @param currentVersion this build's `versionName`.
     * @param nagFloor `android_min_supported_version`, or `null` for no gate.
     * @param blockFloor [BLOCKING_KEY], or `null` for no gate. `null` can never block.
     * @param play what Play said.
     */
    fun decide(
        currentVersion: String?,
        nagFloor: String?,
        blockFloor: String?,
        play: PlayState,
    ): Decision {
        // Resuming an interrupted immediate update outranks everything. The
        // alternative is an app that sits on a partially applied update forever
        // because the decision that started it has since gone quiet.
        if (play == PlayState.IN_PROGRESS) return Decision.BLOCK

        val belowBlock = AppVersions.isOlderThan(currentVersion, blockFloor)
        if (belowBlock && play == PlayState.AVAILABLE) return Decision.BLOCK

        // Below the blocking floor but Play has nothing to install: the user
        // cannot comply, so do not wall them in. Say it, loudly but passably.
        if (belowBlock) return Decision.OFFER

        return if (AppVersions.isOlderThan(currentVersion, nagFloor)) {
            Decision.OFFER
        } else {
            Decision.NONE
        }
    }

    /**
     * Pure parse of the blocking key out of a `/v2/config` body, with the same
     * fail-open rules as `MinimumVersionGate.parseMinimumVersion`: every kind of
     * failure is `null`, and `null` is no gate.
     *
     * A floor the app cannot COMPARE is also no gate — `"latest"`, `"1.x"` and
     * `"1.6.39 (80)"` all return `null` rather than being guessed at, because a
     * guess here is a lock-out.
     */
    fun parseBlockingVersion(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val json = org.json.JSONObject(body)
            val raw = json.optString(BLOCKING_KEY).trim()
            val candidate = raw.takeIf { it.isNotEmpty() && it != "null" }
            if (AppVersions.parse(candidate) == null) null else candidate
        } catch (_: Exception) {
            null
        }
    }
}
