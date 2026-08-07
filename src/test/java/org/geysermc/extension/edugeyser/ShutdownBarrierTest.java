package org.geysermc.extension.edugeyser;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownBarrierTest {

    @Test
    void shutdownWaitsForExistingWorkBeforeCleanup() throws Exception {
        ShutdownBarrier barrier = new ShutdownBarrier();
        ShutdownBarrier.Lease lease = barrier.tryEnter();
        assertNotNull(lease);

        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CompletableFuture<Boolean> shutdown = CompletableFuture.supplyAsync(() -> {
            barrier.requestShutdown();
            return barrier.afterQuiescence(cleanupStarted::countDown, 5, TimeUnit.SECONDS);
        });

        assertTrue(waitUntil(barrier::isShutdownRequested));
        assertFalse(cleanupStarted.await(100, TimeUnit.MILLISECONDS));

        lease.close();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertTrue(shutdown.join()));
        assertTrue(cleanupStarted.await(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void cleanupIsSkippedWhenWorkOutlastsItsBudget() throws Exception {
        ShutdownBarrier barrier = new ShutdownBarrier();
        ShutdownBarrier.Lease lease = barrier.tryEnter();
        assertNotNull(lease);
        barrier.requestShutdown();

        CountDownLatch cleanupStarted = new CountDownLatch(1);
        // The lease is still held, so the budget must expire and shutdown must
        // continue without running the cleanup rather than waiting it out.
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertFalse(barrier.afterQuiescence(cleanupStarted::countDown, 50, TimeUnit.MILLISECONDS)));
        assertFalse(cleanupStarted.await(0, TimeUnit.MILLISECONDS));

        lease.close();
    }

    @Test
    void shutdownRejectsNewWork() {
        ShutdownBarrier barrier = new ShutdownBarrier();
        barrier.requestShutdown();

        assertNull(barrier.tryEnter());
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        return condition.getAsBoolean();
    }
}
