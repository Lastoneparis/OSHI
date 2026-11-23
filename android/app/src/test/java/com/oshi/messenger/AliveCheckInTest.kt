package com.oshi.messenger

import com.oshi.messenger.data.model.AliveCheckInEvent
import com.oshi.messenger.data.model.AliveCheckInInterval
import com.oshi.messenger.data.model.AliveCheckInPolicy
import com.oshi.messenger.data.model.AliveCheckInPolicy.Status
import com.oshi.messenger.data.model.OSHIControlPayload
import com.oshi.messenger.service.AliveCheckInStore
import com.oshi.messenger.service.AliveCheckInWatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * __ALIVE_CHECK_IN_2026_11_04__ The liveness check-in, end to end.
 *
 * Covers the five scenarios the feature has to survive — normal, overdue,
 * recovery, offline, and a duplicate arriving over a second transport — plus the
 * three failures that are invisible on a screen: a peer's wrong clock, a
 * deadline computed against a local calendar, and a second notification for one
 * silence.
 */
class AliveCheckInTest {

    private val day = AliveCheckInInterval.DAY_SECONDS
    private val t0 = 1_762_300_000_000L // a fixed instant, so nothing here reads the clock

    @After
    fun tearDown() {
        AliveCheckInStore.resetForTests()
    }

    // ── Wire format ──

    @Test
    fun `an event round-trips through its own JSON`() {
        val sent = AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, day, t0)
        val back = AliveCheckInEvent.fromMessageString(sent.toMessageString())
        assertNotNull(back)
        assertEquals(AliveCheckInEvent.Kind.CONFIRMED, back!!.kind)
        assertEquals(day, back.intervalSeconds)
        // Milliseconds survive the trip. They are what makes two events in the
        // same second distinguishable, which `accepts` depends on.
        assertEquals(t0 / 1000.0, back.atUnixSeconds, 0.0005)
    }

    @Test
    fun `the wire shape is the one the other platforms parse`() {
        val wire = AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, day, t0).toMessageString()
        assertTrue(wire.startsWith(OSHIControlPayload.ALIVE_CHECK_IN))
        val body = JSONObject(OSHIControlPayload.body(wire, OSHIControlPayload.ALIVE_CHECK_IN)!!)
        assertEquals(1, body.getInt("v"))
        assertEquals("on", body.getString("t"))
        assertEquals(day, body.getLong("iv"))
        // SECONDS, not millis: iOS's native Date is seconds-based and this JSON
        // crosses to it. A millis value here would place every check-in in the
        // year 57000 on the other side.
        assertEquals(t0 / 1000.0, body.getDouble("at"), 0.0005)
    }

    @Test
    fun `a disabled event carries no interval`() {
        val off = AliveCheckInEvent.create(AliveCheckInEvent.Kind.DISABLED, day, t0)
        assertEquals(0L, off.intervalSeconds)
        assertFalse(off.isConfirmation)
    }

    @Test
    fun `an unparseable or unknown payload is refused rather than guessed`() {
        // No timestamp: the event cannot be placed in time, and defaulting to
        // "now" would fabricate a fresh confirmation out of a corrupt payload.
        assertNull(AliveCheckInEvent.fromMessageString(
            OSHIControlPayload.ALIVE_CHECK_IN + """{"v":1,"t":"ok","iv":86400}"""
        ))
        // A version from the future: a field we do not understand could change
        // what "ok" means.
        assertNull(AliveCheckInEvent.fromMessageString(
            OSHIControlPayload.ALIVE_CHECK_IN + """{"v":2,"t":"ok","at":1.0,"iv":86400}"""
        ))
        // A kind we do not know.
        assertNull(AliveCheckInEvent.fromMessageString(
            OSHIControlPayload.ALIVE_CHECK_IN + """{"v":1,"t":"maybe","at":1.0,"iv":86400}"""
        ))
        assertNull(AliveCheckInEvent.fromMessageString("hello"))
        assertFalse(AliveCheckInEvent.isEvent("hello"))
    }

    @Test
    fun `a sentinel in the middle of prose is prose`() {
        val typed = "we should add a ${OSHIControlPayload.ALIVE_CHECK_IN} marker"
        assertFalse(AliveCheckInEvent.isEvent(typed))
        assertEquals(typed, OSHIControlPayload.previewText(typed))
    }

    // ── The catalog ──

    @Test
    fun `the catalog claims the sentinel exactly once, as rendered`() {
        val entries = OSHIControlPayload.catalog.filter { it.prefix == OSHIControlPayload.ALIVE_CHECK_IN }
        assertEquals(1, entries.size)
        assertEquals(OSHIControlPayload.Kind.RENDERED, entries.first().kind)
    }

    @Test
    fun `a check-in is neither silent nor push-suppressed`() {
        val wire = AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, day, t0).toMessageString()
        // Rendered: it is a line in the thread. A confirmation the reader cannot
        // find afterwards is one they have to take on trust from a coloured dot.
        assertFalse(OSHIControlPayload.isSilent(wire))
        // And it wakes the peer. This is the one message whose ABSENCE is the
        // alarm, so suppressing its push would be suppressing the feature.
        assertFalse(OSHIControlPayload.suppressesPush(wire))
    }

    @Test
    fun `each of the three events gets its own line, and a corrupt body still gets one`() {
        val labels = OSHIControlPayload.Labels()
        fun row(json: String) = OSHIControlPayload.listPreview(OSHIControlPayload.ALIVE_CHECK_IN + json, labels)

        assertEquals("💓 " + labels.aliveCheckInStarted, row("""{"v":1,"t":"on","at":1.0,"iv":86400}"""))
        assertEquals("💓 " + labels.aliveCheckInConfirmed, row("""{"v":1,"t":"ok","at":1.0,"iv":86400}"""))
        assertEquals("💓 " + labels.aliveCheckInStopped, row("""{"v":1,"t":"off","at":1.0,"iv":0}"""))
        // Not valid JSON at all. It must not print the payload, and it must not
        // print "stopped" — claiming a schedule ended when we cannot tell is the
        // reading that costs something.
        assertEquals("💓 " + labels.aliveCheckInConfirmed, row("}{"))
    }

    // ── Status, and the time zones ──

    @Test
    fun `nothing is shown until something is configured`() {
        assertEquals(Status.NEUTRAL, AliveCheckInPolicy.status(false, t0, day, t0))
        assertEquals(Status.NEUTRAL, AliveCheckInPolicy.status(true, 0L, day, t0))
        assertEquals(Status.NEUTRAL, AliveCheckInPolicy.status(true, t0, 0L, t0))
    }

    @Test
    fun `confirmed before the deadline, overdue at it and after it`() {
        val due = AliveCheckInPolicy.dueAtMillis(t0, day)
        assertEquals(Status.CONFIRMED, AliveCheckInPolicy.status(true, t0, day, due - 1))
        // At the deadline, not one tick after: "due in 0" is not a state anyone
        // can act on, and a half-open boundary is the one a test can pin.
        assertEquals(Status.OVERDUE, AliveCheckInPolicy.status(true, t0, day, due))
        assertEquals(Status.OVERDUE, AliveCheckInPolicy.status(true, t0, day, due + 86_400_000))
    }

    @Test
    fun `a deadline is the same instant in every time zone`() {
        val original = TimeZone.getDefault()
        try {
            val answers = listOf("UTC", "Pacific/Kiritimati", "Pacific/Niue", "Asia/Kathmandu").map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                Triple(
                    AliveCheckInPolicy.dueAtMillis(t0, day),
                    AliveCheckInPolicy.status(true, t0, day, t0 + day * 1000 - 1),
                    AliveCheckInPolicy.remainingMillis(t0, day, t0),
                )
            }.distinct()
            // One distinct answer across a +14, a -11 and a :45 offset. Absolute
            // instants throughout is the whole reason: no local calendar is
            // consulted to compute or compare a deadline, so daylight saving and
            // a traveller crossing six zones cannot move one.
            assertEquals(1, answers.size)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // ── Another device's clock ──

    @Test
    fun `a clock from the future cannot keep a conversation green`() {
        val received = t0
        val declared = t0 + 7L * 24 * 3_600 * 1_000 // a week ahead
        // Clamped to receipt. This is the dangerous direction: honoured raw, the
        // deadline would move a week out and the indicator would stay green long
        // past the point where it should have gone red.
        assertEquals(received, AliveCheckInPolicy.confirmedAt(declared, received))
    }

    @Test
    fun `a clock years behind tells us nothing about time`() {
        val received = t0
        val declared = t0 - 400L * 24 * 3_600 * 1_000
        assertEquals(received, AliveCheckInPolicy.confirmedAt(declared, received))
    }

    @Test
    fun `an ordinary queue delay is honoured, because that lateness is the point`() {
        val received = t0
        val declared = t0 - 6 * 3_600 * 1_000 // six hours in a relay queue
        // Dating this to delivery would hide exactly what the feature exists to
        // show: the confirmation is six hours old, and the countdown must say so.
        assertEquals(declared, AliveCheckInPolicy.confirmedAt(declared, received))
    }

    @Test
    fun `a missing timestamp falls back to the receive instant`() {
        assertEquals(t0, AliveCheckInPolicy.confirmedAt(0L, t0))
        assertEquals(t0, AliveCheckInPolicy.confirmedAt(-5L, t0))
    }

    // ── Duplicates and ordering ──

    @Test
    fun `a replay of the event we already hold changes nothing`() {
        assertFalse(AliveCheckInPolicy.accepts(100.0, 100.0))
        assertFalse(AliveCheckInPolicy.accepts(99.9, 100.0))
        assertTrue(AliveCheckInPolicy.accepts(100.1, 100.0))
        assertTrue(AliveCheckInPolicy.accepts(1.0, 0.0))
    }

    @Test
    fun `the same send arriving over three transports is applied once`() {
        val peer = "PEERKEY"
        val event = AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, day, t0)
        val first = AliveCheckInStore.applyIncoming(event, peer, receivedAtMillis = t0)
        val second = AliveCheckInStore.applyIncoming(event, peer, receivedAtMillis = t0 + 50)
        val third = AliveCheckInStore.applyIncoming(event, peer, receivedAtMillis = t0 + 900)
        assertTrue(first.accepted)
        assertFalse(second.accepted)
        assertFalse(third.accepted)
        assertEquals(t0, AliveCheckInStore.entryFor(peer)!!.theirsLastConfirmedAtMillis)
    }

    @Test
    fun `an older event arriving late cannot pull a confirmation backwards`() {
        val peer = "PEERKEY"
        val newer = AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, day, t0)
        val older = AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, day, t0 - 3_600_000)
        AliveCheckInStore.applyIncoming(newer, peer, receivedAtMillis = t0)
        val late = AliveCheckInStore.applyIncoming(older, peer, receivedAtMillis = t0 + 10)
        assertFalse(late.accepted)
        // Had it been applied, the deadline would have moved an hour EARLIER and
        // manufactured an overdue state out of unordered mesh delivery.
        assertEquals(t0, AliveCheckInStore.entryFor(peer)!!.theirsLastConfirmedAtMillis)
    }

    // ── Recovery ──

    @Test
    fun `answering after the deadline is a recovery, answering on time is not`() {
        val peer = "PEERKEY"
        AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, day, t0), peer, t0
        )
        val onTime = AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, day, t0 + 1_000), peer, t0 + 1_000
        )
        assertFalse(onTime.isRecovery)

        // Now let the deadline pass, then answer.
        val late = t0 + day * 1_000 + 6 * 3_600_000
        val recovered = AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.CONFIRMED, day, late), peer, late
        )
        assertTrue(recovered.accepted)
        assertTrue(recovered.wasOverdue)
        assertTrue(recovered.isRecovery)
        assertEquals(Status.CONFIRMED, AliveCheckInStore.entryFor(peer)!!.theirStatus(late))
    }

    @Test
    fun `turning a schedule off is not a recovery, and clears their half`() {
        val peer = "PEERKEY"
        AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, day, t0), peer, t0
        )
        val overdueNow = t0 + day * 1_000 + 1
        val off = AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.DISABLED, 0L, overdueNow), peer, overdueNow
        )
        assertTrue(off.accepted)
        assertFalse(off.isRecovery)
        val e = AliveCheckInStore.entryFor(peer)!!
        assertFalse(e.theirsEnabled)
        assertEquals(Status.NEUTRAL, e.theirStatus(overdueNow))
    }

    @Test
    fun `a confirmation with no interval keeps the one we already knew`() {
        val peer = "PEERKEY"
        AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, day, t0), peer, t0
        )
        // An older build, or a payload whose `iv` was lost. Falling to zero would
        // read as "nothing is configured" and silently retire the schedule.
        val stripped = AliveCheckInEvent(
            kind = AliveCheckInEvent.Kind.CONFIRMED,
            atUnixSeconds = (t0 + 1_000) / 1000.0,
            intervalSeconds = 0L,
        )
        AliveCheckInStore.applyIncoming(stripped, peer, t0 + 1_000)
        assertEquals(day, AliveCheckInStore.entryFor(peer)!!.theirsIntervalSeconds)
    }

    // ── One notification per silence ──

    @Test
    fun `an overdue notice fires once per deadline, and again for the next one`() {
        val due1 = t0 + day * 1_000
        val due2 = due1 + day * 1_000
        assertTrue(AliveCheckInPolicy.shouldNotifyOverdue(due1, 0L))
        assertFalse(AliveCheckInPolicy.shouldNotifyOverdue(due1, due1))
        assertTrue(AliveCheckInPolicy.shouldNotifyOverdue(due2, due1))
    }

    @Test
    fun `two callers waking at the same instant cannot both notify`() {
        val peer = "PEERKEY"
        AliveCheckInStore.applyIncoming(
            AliveCheckInEvent.create(AliveCheckInEvent.Kind.ENABLED, day, t0), peer, t0
        )
        val due = AliveCheckInPolicy.dueAtMillis(t0, day)
        // The WorkManager wakeup and the app coming to the foreground are a real
        // pair, and ask-then-mark would let both read "not yet notified".
        assertTrue(AliveCheckInStore.claimTheirOverdueNotice(peer, due))
        assertFalse(AliveCheckInStore.claimTheirOverdueNotice(peer, due))
    }

    @Test
    fun `a conversation with no entry claims nothing`() {
        assertFalse(AliveCheckInStore.claimTheirOverdueNotice("NOBODY", t0))
        assertFalse(AliveCheckInStore.claimMyDueReminder("NOBODY", t0))
    }

    // ── Our own half ──

    @Test
    fun `enabling counts as a confirmation, so a schedule never starts overdue`() {
        val peer = "PEERKEY"
        val event = AliveCheckInStore.enableMine(peer, day, t0)
        assertEquals(AliveCheckInEvent.Kind.ENABLED, event.kind)
        val e = AliveCheckInStore.entryFor(peer)!!
        assertEquals(Status.CONFIRMED, e.myStatus(t0))
        assertEquals(t0, e.mineLastConfirmedAtMillis)
    }

    @Test
    fun `pressing the button in an unconfigured conversation starts one`() {
        val peer = "PEERKEY"
        // Refusing because of a flag the user never saw would read as a dead
        // button; someone who taps "I'm alive" means to start checking in.
        val event = AliveCheckInStore.confirmMine(peer, t0)
        assertNotNull(event)
        assertEquals(AliveCheckInEvent.Kind.CONFIRMED, event!!.kind)
        assertEquals(AliveCheckInInterval.DEFAULT, event.intervalSeconds)
        assertTrue(AliveCheckInStore.entryFor(peer)!!.mineEnabled)
    }

    @Test
    fun `confirming moves our deadline and re-arms our own reminder`() {
        val peer = "PEERKEY"
        AliveCheckInStore.enableMine(peer, day, t0)
        val due1 = AliveCheckInPolicy.dueAtMillis(t0, day)
        assertTrue(AliveCheckInStore.claimMyDueReminder(peer, due1))
        AliveCheckInStore.confirmMine(peer, due1 + 60_000)
        val e = AliveCheckInStore.entryFor(peer)!!
        // The claim is cleared, so the NEXT deadline can still raise its own
        // reminder rather than being swallowed by the previous one.
        assertEquals(0L, e.notifiedMyDueForDueAtMillis)
        assertEquals(Status.CONFIRMED, e.myStatus(due1 + 60_000))
    }

    @Test
    fun `an interval is clamped to something a human can meet`() {
        assertEquals(AliveCheckInInterval.MIN_SECONDS, AliveCheckInInterval.clamp(0L))
        assertEquals(AliveCheckInInterval.MIN_SECONDS, AliveCheckInInterval.clamp(-5L))
        assertEquals(AliveCheckInInterval.MAX_SECONDS, AliveCheckInInterval.clamp(10_000 * day))
        assertEquals(day, AliveCheckInInterval.clamp(day))
        assertTrue(AliveCheckInInterval.isPreset(AliveCheckInInterval.EVERY_72H))
        assertFalse(AliveCheckInInterval.isPreset(8 * AliveCheckInInterval.HOUR_SECONDS))
    }

    @Test
    fun `a custom interval survives the store`() {
        val peer = "PEERKEY"
        val eight = 8 * AliveCheckInInterval.HOUR_SECONDS
        AliveCheckInStore.enableMine(peer, eight, t0)
        assertEquals(eight, AliveCheckInStore.entryFor(peer)!!.mineIntervalSeconds)
    }

    // ── What one row shows, out of two directions ──

    @Test
    fun `a silence on either side wins the row`() {
        val both = AliveCheckInStore.Entry(
            mineEnabled = true, mineIntervalSeconds = day, mineLastConfirmedAtMillis = t0,
            theirsEnabled = true, theirsIntervalSeconds = day, theirsLastConfirmedAtMillis = t0,
        )
        assertEquals(Status.CONFIRMED, both.rowStatus(t0 + 1_000))
        // Our own deadline passes while theirs holds. Showing their green would
        // hide the only half the user can still do something about.
        val myDeadline = t0 + day * 1_000 + 1
        val theirsFresher = both.copy(theirsLastConfirmedAtMillis = myDeadline)
        assertEquals(Status.OVERDUE, theirsFresher.rowStatus(myDeadline))
        // And the other way round.
        val mineFresher = both.copy(mineLastConfirmedAtMillis = myDeadline)
        assertEquals(Status.OVERDUE, mineFresher.rowStatus(myDeadline))
    }

    @Test
    fun `the row counts down to the nearest deadline of the two`() {
        val e = AliveCheckInStore.Entry(
            mineEnabled = true, mineIntervalSeconds = 3 * day, mineLastConfirmedAtMillis = t0,
            theirsEnabled = true, theirsIntervalSeconds = day, theirsLastConfirmedAtMillis = t0,
        )
        assertEquals(AliveCheckInPolicy.dueAtMillis(t0, day), e.rowDueAtMillis())
        // Only one side configured: that side's deadline, not zero.
        val mineOnly = e.copy(theirsEnabled = false)
        assertEquals(AliveCheckInPolicy.dueAtMillis(t0, 3 * day), mineOnly.rowDueAtMillis())
        assertEquals(0L, AliveCheckInStore.Entry().rowDueAtMillis())
    }

    // ── The single alarm ──

    @Test
    fun `the watcher arms itself for the soonest deadline ahead, across conversations`() {
        val soon = t0 + 2 * 3_600_000
        val later = t0 + 20 * 3_600_000
        val entries = mapOf(
            "A" to AliveCheckInStore.Entry(
                theirsEnabled = true, theirsIntervalSeconds = 20 * 3_600,
                theirsLastConfirmedAtMillis = t0,
            ),
            "B" to AliveCheckInStore.Entry(
                mineEnabled = true, mineIntervalSeconds = 2 * 3_600,
                mineLastConfirmedAtMillis = t0,
            ),
            // Idle, and a deadline already in the past: neither is something to
            // wake up FOR. The past one has already been notified or will be
            // caught by the sweep at app start.
            "C" to AliveCheckInStore.Entry(),
            "D" to AliveCheckInStore.Entry(
                mineEnabled = true, mineIntervalSeconds = 3_600,
                mineLastConfirmedAtMillis = t0 - 10 * 3_600_000,
            ),
        )
        assertEquals(soon, AliveCheckInWatcher.nextDeadlineMillis(entries, t0))
        // Once the soon one is gone, the later one.
        assertEquals(later, AliveCheckInWatcher.nextDeadlineMillis(entries, soon))
        assertNull(AliveCheckInWatcher.nextDeadlineMillis(entries, later + 1))
        assertNull(AliveCheckInWatcher.nextDeadlineMillis(emptyMap(), t0))
    }

    // ── The countdown's words ──

    @Test
    fun `a countdown shows the two largest units and stops there`() {
        fun b(ms: Long) = AliveCheckInPolicy.breakdown(ms)
        assertEquals(AliveCheckInPolicy.Breakdown(2, 7, 0), b((2 * 86_400 + 7 * 3_600 + 14 * 60 + 3) * 1_000L))
        assertEquals(AliveCheckInPolicy.Breakdown(0, 5, 30), b((5 * 3_600 + 30 * 60 + 59) * 1_000L))
        assertEquals(AliveCheckInPolicy.Breakdown(0, 0, 45), b(45 * 60_000L))
        assertTrue(b(30_000L).isImminent)
        // A time PAST the deadline is described by the same two numbers: the sign
        // lives in the sentence ("due in" / "no response for"), not in the units.
        assertEquals(b(5 * 3_600_000L), b(-5 * 3_600_000L))
    }

    @Test
    fun `remaining is negative exactly as far past the deadline as it is`() {
        val due = AliveCheckInPolicy.dueAtMillis(t0, day)
        assertEquals(0L, AliveCheckInPolicy.remainingMillis(t0, day, due))
        assertEquals(-3_600_000L, AliveCheckInPolicy.remainingMillis(t0, day, due + 3_600_000))
        assertEquals(3_600_000L, AliveCheckInPolicy.remainingMillis(t0, day, due - 3_600_000))
    }
}
