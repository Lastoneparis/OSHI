package com.oshi.messenger.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.*
import android.util.Log

class OshiConnectionService : ConnectionService() {
    companion object {
        private const val TAG = "OshiConnectionService"
        const val EXTRA_CALLER_KEY = "caller_key"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_IS_VIDEO = "is_video"

        fun getPhoneAccountHandle(context: Context): PhoneAccountHandle {
            val componentName = ComponentName(context, OshiConnectionService::class.java)
            return PhoneAccountHandle(componentName, "OSHI")
        }

        fun registerPhoneAccount(context: Context) {
            try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val handle = getPhoneAccountHandle(context)
                val account = PhoneAccount.builder(handle, "OSHI Messenger")
                    .setCapabilities(
                        PhoneAccount.CAPABILITY_CALL_PROVIDER or
                        PhoneAccount.CAPABILITY_SELF_MANAGED
                    )
                    .addSupportedUriScheme(PhoneAccount.SCHEME_SIP)
                    .build()
                telecomManager.registerPhoneAccount(account)
                Log.d(TAG, "PhoneAccount registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PhoneAccount: ${e.message}")
            }
        }

        fun addIncomingCall(context: Context, callerKey: String, callerName: String, callId: String, isVideo: Boolean) {
            try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val extras = Bundle().apply {
                    putString(EXTRA_CALLER_KEY, callerKey)
                    putString(EXTRA_CALLER_NAME, callerName)
                    putString(EXTRA_CALL_ID, callId)
                    putBoolean(EXTRA_IS_VIDEO, isVideo)
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getPhoneAccountHandle(context))
                }
                telecomManager.addNewIncomingCall(getPhoneAccountHandle(context), extras)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add incoming call via Telecom: ${e.message}")
                // Fall back to IncomingCallService notification
                // (this happens if SELF_MANAGED is not supported or user hasn't granted permission)
            }
        }

        fun placeOutgoingCall(context: Context, peerKey: String, peerName: String, isVideo: Boolean) {
            try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val extras = Bundle().apply {
                    putString(EXTRA_CALLER_KEY, peerKey)
                    putString(EXTRA_CALLER_NAME, peerName)
                    putBoolean(EXTRA_IS_VIDEO, isVideo)
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, getPhoneAccountHandle(context))
                }
                telecomManager.placeCall(Uri.fromParts("sip", peerKey.take(16), null), extras)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to place call via Telecom: ${e.message}")
            }
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras ?: Bundle()
        val callerKey = extras.getString(EXTRA_CALLER_KEY, "")
        val callerName = extras.getString(EXTRA_CALLER_NAME, "Unknown")
        val callId = extras.getString(EXTRA_CALL_ID, "")
        val isVideo = extras.getBoolean(EXTRA_IS_VIDEO, false)

        Log.d(TAG, "onCreateIncomingConnection: $callerName ($callId)")

        val connection = OshiConnection(applicationContext, callerKey, callerName, callId, isVideo)
        connection.setInitializing()
        connection.setAddress(Uri.fromParts("sip", callerKey.take(16), null), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        if (isVideo) {
            connection.setVideoState(VideoProfile.STATE_BIDIRECTIONAL)
        }
        connection.setRinging()
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val extras = request?.extras ?: Bundle()
        val peerKey = extras.getString(EXTRA_CALLER_KEY, "")
        val peerName = extras.getString(EXTRA_CALLER_NAME, "Unknown")
        val isVideo = extras.getBoolean(EXTRA_IS_VIDEO, false)

        Log.d(TAG, "onCreateOutgoingConnection: $peerName")

        val connection = OshiConnection(applicationContext, peerKey, peerName, "", isVideo)
        connection.setInitializing()
        connection.setAddress(Uri.fromParts("sip", peerKey.take(16), null), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(peerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setDialing()
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed -- falling back to notification")
        // Fall back to the existing IncomingCallService notification approach
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
    }
}
