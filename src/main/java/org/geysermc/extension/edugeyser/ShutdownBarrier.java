package org.geysermc.extension.edugeyser;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Coordinates normal asynchronous work with an exclusive shutdown cleanup.
 * Work that entered before shutdown is allowed to finish; new work is rejected,
 * and shutdown waits until every existing lease has been released.
 */
final class ShutdownBarrier {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile boolean shutdownRequested;

    boolean isShutdownRequested() {
        return shutdownRequested;
    }

    void requestShutdown() {
        shutdownRequested = true;
    }

    @Nullable Lease tryEnter() {
        Lock readLock = lock.readLock();
        readLock.lock();
        if (shutdownRequested) {
            readLock.unlock();
            return null;
        }
        return new Lease(readLock);
    }

    /**
     * Runs an exclusive cleanup once existing work has finished, waiting no
     * longer than the given budget. Shutdown has to stay responsive, so work
     * that outlasts the budget makes the cleanup a no-op instead of holding
     * the server open behind a slow remote call.
     *
     * @return true if the cleanup ran
     */
    boolean afterQuiescence(Runnable action, long timeout, TimeUnit unit) {
        Lock writeLock = lock.writeLock();
        boolean acquired;
        try {
            acquired = writeLock.tryLock(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            return false;
        }
        try {
            action.run();
        } finally {
            writeLock.unlock();
        }
        return true;
    }

    static final class Lease implements AutoCloseable {
        private final Lock lock;
        private boolean closed;

        private Lease(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }
}
