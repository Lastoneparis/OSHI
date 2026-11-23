package com.oshi.messenger

import com.oshi.messenger.service.audio.AdaptiveBitrateController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression test for the crash that killed a live call on a Galaxy A17,
 * 2026-08-24 11:53:00:
 *
 *   FATAL EXCEPTION: DefaultDispatcher-worker-18
 *   java.lang.NullPointerException: Attempt to invoke virtual method
 *     'boolean java.lang.Boolean.booleanValue()' on a null object reference
 *     at AdaptiveBitrateController.getPacketLossPercent
 *     at EnhancedCallManager$startStatsUpdater$1.invokeSuspend
 *
 * The two sliding windows were plain `ArrayDeque`s written from the audio RX
 * path and read from the stats coroutine. Kotlin's ArrayDeque is a circular
 * array whose `removeFirst()` NULLS the vacated slot, so an iteration racing a
 * trim reads that null — and because the element type is the non-null `Boolean`,
 * the compiler emits an unchecked unboxing, which throws.
 *
 * These tests reproduce that race: one thread reports packets (forcing the
 * window to trim past `windowSize`) while another reads the derived values.
 * Without the lock this fails with NPE or ConcurrentModificationException;
 * with it, every read returns a sane number.
 */
class AdaptiveBitrateConcurrencyTest {

    @Test
    fun `reading loss and latency while packets are reported never throws`() {
        val c = AdaptiveBitrateController()
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)

        // Two writers on the audio RX path.
        repeat(2) { w ->
            pool.execute {
                try {
                    start.await()
                    repeat(20_000) { i ->
                        // Mix received and lost so the window is never uniform,
                        // and exceed windowSize (50) so trimWindow keeps calling
                        // removeFirst() — the operation that nulls a slot.
                        if ((i + w) % 7 == 0) c.reportPacketLost() else c.reportPacketReceived()
                        c.reportLatency((i % 400).toDouble())
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }

        // Two readers, standing in for EnhancedCallManager.startStatsUpdater.
        repeat(2) {
            pool.execute {
                try {
                    start.await()
                    repeat(20_000) {
                        val loss = c.packetLossPercent
                        val latency = c.averageLatency
                        // A torn read would surface as NaN or an impossible value
                        // even where it did not throw.
                        assertTrue("loss out of range: $loss", loss in 0.0..100.0)
                        assertTrue("latency negative: $latency", latency >= 0.0)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }
        }

        start.countDown()
        assertTrue("threads did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()

        val t = failure.get()
        if (t != null) throw AssertionError("concurrent access threw ${t::class.java.name}: ${t.message}", t)
    }

    @Test
    fun `reset while reporting does not corrupt the window`() {
        val c = AdaptiveBitrateController()
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        pool.execute {
            try {
                start.await()
                repeat(20_000) { c.reportPacketReceived() }
            } catch (t: Throwable) { failure.compareAndSet(null, t) } finally { done.countDown() }
        }
        pool.execute {
            try {
                start.await()
                repeat(5_000) {
                    c.reset()
                    assertTrue(c.packetLossPercent in 0.0..100.0)
                }
            } catch (t: Throwable) { failure.compareAndSet(null, t) } finally { done.countDown() }
        }

        start.countDown()
        assertTrue("threads did not finish", done.await(60, TimeUnit.SECONDS))
        pool.shutdownNow()
        failure.get()?.let { throw AssertionError("reset raced reporting: ${it::class.java.name}", it) }
    }

    @Test
    fun `an empty window reports zero rather than dividing by zero`() {
        val c = AdaptiveBitrateController()
        assertEquals(0.0, c.packetLossPercent, 0.0)
        assertEquals(0.0, c.averageLatency, 0.0)
    }
}
