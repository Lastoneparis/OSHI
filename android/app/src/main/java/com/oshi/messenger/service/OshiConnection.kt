package com.oshi.messenger.service

import android.content.Context
import android.content.Intent
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import android.util.Log
import androidx.core.content.ContextCompat

class OshiConnection(
    private val appContext: Context,
    private val peerKey: String,
    private val peerName: String,
    private val callId: String,
    private val isVideo: Boolean,
    private val isOutgoing: Boolean = false
) : Connection() {

    companion object {
        private const val TAG = "OshiConnection"

        /**
         * __VUPG_TELECOM_2026_09_14__ La connexion Telecom vivante, pour qu'une
         * montée en vidéo EN COURS D'APPEL puisse la mettre à jour.
         *
         * `setVideoState` n'était appelé qu'à `onCreateIncomingConnection`, donc
         * l'interface système restait « appel audio » pendant tout un appel
         * vidéo: mauvaise icône, mauvaise reprise depuis l'écran verrouillé.
         * iOS fait l'équivalent avec `CXCallUpdate.hasVideo` à quatre endroits
         * de la négociation; il n'y avait rien ici.
         *
         * Référence FAIBLE: Telecom possède l'objet et le détruit quand il veut.
         * Une référence forte le retiendrait après la fin de l'appel — une fuite
         * pour un objet système, et sur un objet mort `setVideoState` lèverait.
         */
        @Volatile
        private var live: java.lang.ref.WeakReference<OshiConnection>? = null

        /**
         * Dit à Telecom que cet appel est devenu (ou a cessé d'être) un appel
         * vidéo. Sans effet quand aucune connexion n'est vivante — ce qui est le
         * cas normal hors appel, pas une erreur.
         */
        fun updateCallHasVideo(hasVideo: Boolean) {
            val c = live?.get() ?: return
            try {
                c.videoState = if (hasVideo) VideoProfile.STATE_BIDIRECTIONAL
                               else VideoProfile.STATE_AUDIO_ONLY
                if (hasVideo) {
                    c.connectionCapabilities =
                        c.connectionCapabilities or CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL
                }
                Log.d(TAG, "DIAG_VUPGRADE_TELECOM | hasVideo=$hasVideo")
            } catch (t: Throwable) {
                // Une connexion que Telecom a déjà démontée lève ici. Ce n'est pas
                // une condition d'erreur: l'appel est fini, il n'y a plus rien à
                // mettre à jour.
                Log.d(TAG, "DIAG_VUPGRADE_TELECOM | connexion morte: ${t.message}")
            }
        }
    }

    private val callStartTime = System.currentTimeMillis()

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        if (isVideo) {
            connectionCapabilities = connectionCapabilities or CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL
        }
        audioModeIsVoip = true
        live = java.lang.ref.WeakReference(this)
    }

    /** Call log integration removed — OSHI manages its own call history.
     *  Avoids WRITE_CALL_LOG / READ_CALL_LOG permissions (Google Play policy). */
    private fun addToCallLog(callType: Int) {
        // No-op: call history is tracked internally by OSHI
        Log.d(TAG, "Call ended: $peerName (type=$callType)")
    }

    override fun onAnswer(videoState: Int) {
        Log.d(TAG, "onAnswer (videoState=$videoState)")
        setActive()

        // __ANSWER_OPENED_NOTHING_2026_11_03__ Anatole: "quand on décroche un
        // appel, cela ne s'ouvre pas correctement (ni l'application ni
        // l'appel) et la photo de l'interlocuteur ne s'affiche pas."
        //
        // This connection is PROPERTY_SELF_MANAGED (see `init`), which means
        // Telecom shows NO call UI of its own — answering is handed to us and
        // we are expected to present the call. Until now `onAnswer` called
        // `setActive()` and broadcast, and broadcast ONLY: nothing in the app
        // receives `CALL_ACCEPTED` (grep it — this is its single occurrence),
        // and nothing started an activity. So an answer that arrived through
        // Telecom — the lock screen, a headset button, a watch, Android Auto —
        // brought up audio and no screen at all. The microphone was live and
        // the user was looking at their home screen.
        //
        // That is also what the server saw in the 2/5 complaint on call
        // 1e0aa75b: the Android side transmitted 442 Opus frames (23 % voice,
        // 92 % peak — they were talking) while the call was recorded
        // `callDeclined` with a duration of 0:00.
        //
        // `IncomingCallService.ACTION_ACCEPT` already knows how to do this, so
        // the extras below are deliberately identical to its own, including
        // the caller key the in-call UI resolves the photo from.
        runCatching {
            appContext.startActivity(
                Intent(appContext, com.oshi.messenger.ui.MainActivity::class.java).apply {
                    setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra("incoming_call", true)
                    putExtra(IncomingCallService.EXTRA_CALLER_KEY, peerKey)
                    putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
                    putExtra(IncomingCallService.EXTRA_CALLER_NAME, peerName)
                    putExtra(IncomingCallService.EXTRA_IS_VIDEO, isVideo)
                }
            )
        }.onFailure {
            // Android 10+ can refuse a background activity start. The answer
            // still stands and the audio still runs, so losing the screen must
            // not lose the call; the notification's own full-screen intent is
            // the other way in.
            Log.w(TAG, "onAnswer could not bring up the in-call UI: ${it.message}")
        }

        // Kept deliberately even though nothing receives it today: removing a
        // broadcast is only safe once you know no out-of-tree receiver (the
        // share extension, a future Wear app) listens for it.
        appContext.sendBroadcast(Intent("com.oshi.messenger.CALL_ACCEPTED").apply {
            putExtra(IncomingCallService.EXTRA_CALLER_KEY, peerKey)
            putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
            setPackage(appContext.packageName)
        })
    }

    override fun onAnswer() {
        onAnswer(if (isVideo) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY)
    }

    override fun onReject() {
        Log.d(TAG, "onReject")
        addToCallLog(if (isOutgoing) 2 /* OUTGOING */ else 3 /* MISSED */)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        appContext.sendBroadcast(Intent("com.oshi.messenger.CALL_DECLINED").apply {
            putExtra(IncomingCallService.EXTRA_CALLER_KEY, peerKey)
            putExtra(IncomingCallService.EXTRA_CALL_ID, callId)
            setPackage(appContext.packageName)
        })
    }

    override fun onDisconnect() {
        if (live?.get() === this) live = null
        Log.d(TAG, "onDisconnect")
        addToCallLog(if (isOutgoing) 2 /* OUTGOING */ else 1 /* INCOMING */)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        appContext.sendBroadcast(Intent("com.oshi.messenger.END_CALL").apply {
            putExtra(IncomingCallService.EXTRA_CALLER_KEY, peerKey)
            setPackage(appContext.packageName)
        })
    }

    override fun onHold() {
        Log.d(TAG, "onHold")
        setOnHold()
    }

    override fun onUnhold() {
        Log.d(TAG, "onUnhold")
        setActive()
    }

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        Log.d(TAG, "onCallAudioStateChanged: ${state?.route}")
    }

    fun setCallActive() {
        setActive()
    }

    fun endCall(cause: Int = DisconnectCause.REMOTE) {
        addToCallLog(if (isOutgoing) 2 /* OUTGOING */ else 1 /* INCOMING */)
        setDisconnected(DisconnectCause(cause))
        destroy()
    }
}
