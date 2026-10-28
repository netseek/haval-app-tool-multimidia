package br.com.redesurftank.havalshisuku.experimental;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class CarPlayConsumerRetirementTest {
    @Test
    public void detachedConsumerKeepsResourcesUntilProducerStopped() {
        AtomicInteger surfaces = new AtomicInteger();
        AtomicInteger textures = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CarPlayConsumerRetirement retirement = new CarPlayConsumerRetirement(
            surfaces::incrementAndGet, textures::incrementAndGet, finished::incrementAndGet);
        retirement.detached();
        retirement.detached();
        assertEquals(0, surfaces.get());
        assertEquals(0, textures.get());
        assertFalse(retirement.isStopped());
        retirement.confirmStopped();
        retirement.confirmStopped();
        assertEquals(1, surfaces.get());
        assertEquals(1, textures.get());
        assertEquals(1, finished.get());
        assertTrue(retirement.isFinished());
    }

    @Test
    public void stoppedProducerKeepsTextureWhileViewStillAttached() {
        AtomicInteger surfaces = new AtomicInteger();
        AtomicInteger textures = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CarPlayConsumerRetirement retirement = new CarPlayConsumerRetirement(
            surfaces::incrementAndGet, textures::incrementAndGet, finished::incrementAndGet);
        retirement.confirmStopped();
        assertEquals(1, surfaces.get());
        assertEquals(0, textures.get());
        assertEquals(0, finished.get());
        retirement.detached();
        assertEquals(1, textures.get());
        assertTrue(retirement.isFinished());
    }

    @Test(timeout = 3000)
    public void concurrentDetachAndStopReleaseOnlyOnce() {
        AtomicInteger surfaces = new AtomicInteger();
        AtomicInteger textures = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CarPlayConsumerRetirement retirement = new CarPlayConsumerRetirement(
            surfaces::incrementAndGet, textures::incrementAndGet, finished::incrementAndGet);
        CompletableFuture<Void> detached = CompletableFuture.runAsync(retirement::detached);
        CompletableFuture<Void> stopped = CompletableFuture.runAsync(retirement::confirmStopped);
        CompletableFuture.allOf(detached, stopped).join();
        assertEquals(1, surfaces.get());
        assertEquals(1, textures.get());
        assertEquals(1, finished.get());
    }

    @Test
    public void failedReleaseDoesNotFinishOrReleaseTextureBeforeRetry() {
        AtomicBoolean failing = new AtomicBoolean(true);
        AtomicInteger textures = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        CarPlayConsumerRetirement retirement = new CarPlayConsumerRetirement(() -> {
            if (failing.get()) { throw new IllegalStateException("synthetic release failure"); }
        }, textures::incrementAndGet, finished::incrementAndGet);
        retirement.detached();
        try { retirement.confirmStopped(); fail("Expected failed release"); }
        catch (IllegalStateException expected) {}
        assertFalse(retirement.isFinished());
        assertEquals(0, textures.get());
        failing.set(false);
        retirement.confirmStopped();
        assertEquals(1, textures.get());
        assertEquals(1, finished.get());
    }
}
