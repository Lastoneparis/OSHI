package com.oshi.messenger.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oshi.messenger.network.vps.VPSClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduledCallManager @Inject constructor(
    private val context: Context,
    private val vpsClient: VPSClient,
    private val database: com.oshi.messenger.data.local.AppDatabase
) {
    companion object {
        private const val TAG = "ScheduledCallManager"
        private const val PREFS_NAME = "scheduled_calls"
    }

    enum class CallEventStatus { PENDING, CONFIRMED, CANCELLED, COMPLETED }
    enum class ResponseStatus { PENDING, ACCEPTED, DECLINED }

    data class ScheduledCallEvent(
        val id: String = UUID.randomUUID().toString(),
        val creatorPublicKey: String,
        val peerPublicKey: String,
        val scheduledTime: Long,
        val timezone: String = TimeZone.getDefault().id,
        val isVideoCall: Boolean = false,
        val status: CallEventStatus = CallEventStatus.PENDING,
        val myResponse: ResponseStatus = ResponseStatus.PENDING,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _scheduledCalls = MutableStateFlow<List<ScheduledCallEvent>>(emptyList())
    val scheduledCalls: StateFlow<List<ScheduledCallEvent>> = _scheduledCalls.asStateFlow()

    init {
        loadFromPrefs()
        // Also load from Room DB and merge (task 3.1.5)
        scope.launch {
            val roomRecords = database.scheduledCallDao().getAllScheduledCallsList()
            if (roomRecords.isNotEmpty()) {
                val roomEvents = roomRecords.map { it.toEvent() }
                val prefs = _scheduledCalls.value
                val merged = (prefs + roomEvents).distinctBy { it.id }.sortedBy { it.scheduledTime }
                _scheduledCalls.value = merged
            }
        }
    }

    private fun ScheduledCallEvent.toRecord() = com.oshi.messenger.data.local.ScheduledCallRecord(
        id = id,
        creatorPublicKey = creatorPublicKey,
        peerPublicKey = peerPublicKey,
        scheduledTime = scheduledTime,
        timezone = timezone,
        isVideoCall = isVideoCall,
        status = status.name,
        myResponse = myResponse.name,
        createdAt = createdAt
    )

    private fun com.oshi.messenger.data.local.ScheduledCallRecord.toEvent() = ScheduledCallEvent(
        id = id,
        creatorPublicKey = creatorPublicKey,
        peerPublicKey = peerPublicKey,
        scheduledTime = scheduledTime,
        timezone = timezone,
        isVideoCall = isVideoCall,
        status = CallEventStatus.valueOf(status),
        myResponse = ResponseStatus.valueOf(myResponse),
        createdAt = createdAt
    )

    fun createScheduledCall(
        peerPublicKey: String,
        scheduledTime: Long,
        isVideoCall: Boolean = false,
        myPublicKey: String
    ): ScheduledCallEvent {
        val event = ScheduledCallEvent(
            creatorPublicKey = myPublicKey,
            peerPublicKey = peerPublicKey,
            scheduledTime = scheduledTime,
            isVideoCall = isVideoCall,
            myResponse = ResponseStatus.ACCEPTED
        )

        val current = _scheduledCalls.value.toMutableList()
        current.add(event)
        _scheduledCalls.value = current
        saveToPrefs()

        // Persist to Room (task 3.1.5)
        scope.launch { database.scheduledCallDao().insert(event.toRecord()) }

        scheduleReminders(event)
        notifyPeer(event)

        Log.d(TAG, "Created scheduled call: ${event.id} at ${Date(scheduledTime)}")
        return event
    }

    fun respondToScheduledCall(eventId: String, accept: Boolean) {
        val current = _scheduledCalls.value.toMutableList()
        val index = current.indexOfFirst { it.id == eventId }
        if (index < 0) return

        val event = current[index]
        val updated = event.copy(
            myResponse = if (accept) ResponseStatus.ACCEPTED else ResponseStatus.DECLINED,
            status = if (accept) CallEventStatus.CONFIRMED else CallEventStatus.CANCELLED
        )
        current[index] = updated
        _scheduledCalls.value = current
        saveToPrefs()

        // Persist status update to Room (task 3.1.5)
        scope.launch {
            database.scheduledCallDao().updateStatus(eventId, updated.status.name, updated.myResponse.name)
        }

        // Notify creator of response
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("type", "scheduled_call_response")
                    put("eventId", eventId)
                    put("accepted", accept)
                }
                vpsClient.sendMessage(event.creatorPublicKey, json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send response: ${e.message}")
            }
        }

        Log.d(TAG, "Responded to scheduled call $eventId: accept=$accept")
    }

    fun cancelScheduledCall(eventId: String) {
        val current = _scheduledCalls.value.toMutableList()
        val index = current.indexOfFirst { it.id == eventId }
        if (index < 0) return

        val event = current[index]
        current[index] = event.copy(status = CallEventStatus.CANCELLED)
        _scheduledCalls.value = current
        saveToPrefs()
        cancelReminders(eventId)

        // Persist cancellation to Room (task 3.1.5)
        scope.launch {
            database.scheduledCallDao().updateStatus(eventId, CallEventStatus.CANCELLED.name, event.myResponse.name)
        }

        Log.d(TAG, "Cancelled scheduled call: $eventId")
    }

    /**
     * ⚠️ Currently has ZERO callers: nothing routes an inbound `📅SCHEDULED_CALL📅`
     * payload here — `ChatScreen.kt:2442` renders it as a bubble and that is the whole
     * receive story on Android. The gate below is added anyway so the ingress is safe
     * the day it IS wired, and so this file stops looking like an unguarded entry point.
     */
    fun handleIncomingScheduledCall(json: JSONObject) {
        val event = ScheduledCallEvent(
            id = json.optString("eventId", UUID.randomUUID().toString()),
            creatorPublicKey = json.optString("creatorPublicKey", ""),
            peerPublicKey = json.optString("peerPublicKey", ""),
            scheduledTime = json.optLong("scheduledTime", 0),
            timezone = json.optString("timezone", TimeZone.getDefault().id),
            isVideoCall = json.optBoolean("isVideoCall", false),
            status = CallEventStatus.PENDING
        )

        if (event.scheduledTime == 0L || event.creatorPublicKey.isEmpty()) return

        scope.launch {
            // 🚫 A blocked contact must not be able to plant an alarm on this device.
            // iOS returns before the event is stored, reminded or notified
            // (`ScheduledCallManager.swift:207-211`).
            if (CallAdmissionPolicy.isBlocked(database.contactDao(), event.creatorPublicKey)) {
                Log.w(TAG, "🚫 Ignoring scheduled call from blocked contact ${event.creatorPublicKey.take(10)}…")
                return@launch
            }

            val current = _scheduledCalls.value.toMutableList()
            if (current.any { it.id == event.id }) return@launch // Dedup
            current.add(event)
            _scheduledCalls.value = current
            saveToPrefs()
            scheduleReminders(event)

            Log.d(TAG, "Received scheduled call invitation: ${event.id}")
        }
    }

    private fun scheduleReminders(event: ScheduledCallEvent) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // 15 min reminder
        scheduleAlarm(alarmManager, event.id, event.scheduledTime - 15 * 60 * 1000, 1)
        // 5 min reminder
        scheduleAlarm(alarmManager, event.id, event.scheduledTime - 5 * 60 * 1000, 2)
        // At call time
        scheduleAlarm(alarmManager, event.id, event.scheduledTime, 3)
    }

    private fun scheduleAlarm(alarmManager: AlarmManager, eventId: String, triggerTime: Long, requestCode: Int) {
        if (triggerTime <= System.currentTimeMillis()) return

        val intent = Intent(context, ScheduledCallReceiver::class.java).apply {
            putExtra("eventId", eventId)
            putExtra("reminderType", requestCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            eventId.hashCode() + requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot set exact alarm: ${e.message}")
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun cancelReminders(eventId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        for (rc in 1..3) {
            val intent = Intent(context, ScheduledCallReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, eventId.hashCode() + rc, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pi)
        }
    }

    private fun notifyPeer(event: ScheduledCallEvent) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("type", "scheduled_call_invite")
                    put("eventId", event.id)
                    put("creatorPublicKey", event.creatorPublicKey)
                    put("peerPublicKey", event.peerPublicKey)
                    put("scheduledTime", event.scheduledTime)
                    put("timezone", event.timezone)
                    put("isVideoCall", event.isVideoCall)
                }
                vpsClient.sendMessage(event.peerPublicKey, json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to notify peer: ${e.message}")
            }
        }
    }

    private fun loadFromPrefs() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("events", null) ?: return
            val array = JSONArray(json)
            val events = mutableListOf<ScheduledCallEvent>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                events.add(ScheduledCallEvent(
                    id = obj.getString("id"),
                    creatorPublicKey = obj.getString("creatorPublicKey"),
                    peerPublicKey = obj.getString("peerPublicKey"),
                    scheduledTime = obj.getLong("scheduledTime"),
                    timezone = obj.optString("timezone", TimeZone.getDefault().id),
                    isVideoCall = obj.optBoolean("isVideoCall", false),
                    status = CallEventStatus.valueOf(obj.optString("status", "PENDING")),
                    myResponse = ResponseStatus.valueOf(obj.optString("myResponse", "PENDING")),
                    createdAt = obj.optLong("createdAt", 0)
                ))
            }
            // Remove expired events (older than 1 hour past scheduled time)
            val now = System.currentTimeMillis()
            _scheduledCalls.value = events.filter { it.scheduledTime > now - 3600000 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load scheduled calls: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        try {
            val array = JSONArray()
            for (event in _scheduledCalls.value) {
                array.put(JSONObject().apply {
                    put("id", event.id)
                    put("creatorPublicKey", event.creatorPublicKey)
                    put("peerPublicKey", event.peerPublicKey)
                    put("scheduledTime", event.scheduledTime)
                    put("timezone", event.timezone)
                    put("isVideoCall", event.isVideoCall)
                    put("status", event.status.name)
                    put("myResponse", event.myResponse.name)
                    put("createdAt", event.createdAt)
                })
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("events", array.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save scheduled calls: ${e.message}")
        }
    }
}
