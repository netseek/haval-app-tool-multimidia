package br.com.redesurftank.havalshisuku.navigation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CarPlayCaptureGateTest {
    @Test fun stopRejectsNewCallbacksAndStillAllowsQueuedWorkToDrain() {
        val gate = CarPlayCaptureGate(2)
        assertTrue(gate.acquire())
        gate.close()
        assertFalse(gate.acquire())
        gate.release()
        assertFalse(gate.acquire())
        assertFalse(gate.isOpen())
        assertEquals(0L, gate.dropped)
    }

    @Test fun fullQueueReportsLossAndRecoversCapacity() {
        val gate = CarPlayCaptureGate(1)
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        assertEquals(1L, gate.dropped)
        gate.release()
        assertTrue(gate.acquire())
        gate.release()
    }

    @Test fun concurrentBinderThreadsCannotExceedBoundedCapacity() {
        val gate = CarPlayCaptureGate(8)
        val executor = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(100)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        try {
            repeat(100) {
                executor.execute {
                    if (gate.acquire()) accepted.incrementAndGet()
                    done.countDown()
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(8, accepted.get())
            assertEquals(92L, gate.dropped)
            repeat(8) { gate.release() }
        } finally {
            executor.shutdownNow()
        }
    }
}
