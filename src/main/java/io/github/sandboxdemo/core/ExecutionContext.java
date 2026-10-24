package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** 每次执行共享绝对期限及独立资源账本，不把 Future 完成当作回收证据。 */
public final class ExecutionContext implements SandboxExecutionControl {
    public enum Resource {
        PROCESS,
        STREAM,
        TEMP,
        HOST
    }

    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();
    private final String id = UUID.randomUUID().toString();
    private final long deadline;
    private final long graceNanos;
    private final AtomicReference<SandboxTerminationReason> stopped = new AtomicReference<>();
    private final int[] active = new int[Resource.values().length];
    private final SandboxCleanupState[] settled = new SandboxCleanupState[Resource.values().length];
    private long cleanupDeadline;
    private volatile Runnable stopListener = () -> {};
    private boolean opaque;
    private SandboxCleanupResult reportedCleanup;
    private boolean workerFinished;
    private boolean started;
    private Thread worker;
    private final Runnable whenSettled;
    private boolean capacityReleased;
    private final java.util.Map<java.nio.file.Path, Lease> temporaries = new java.util.HashMap<>();

    public ExecutionContext(Duration timeout, Duration grace) {
        this(timeout, grace, () -> {});
    }

    /** Internal admission hook; must only perform a non-blocking SDK reservation release. */
    public ExecutionContext(Duration timeout, Duration grace, Runnable whenSettled) {
        this.whenSettled = java.util.Objects.requireNonNull(whenSettled, "whenSettled");
        if (timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 24 hours");
        }
        if (grace.isNegative() || grace.isZero() || grace.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "cleanup grace must be positive and at most 30 seconds");
        }
        deadline = System.nanoTime() + timeout.toNanos();
        graceNanos = grace.toNanos();
        java.util.Arrays.fill(settled, SandboxCleanupState.NOT_REQUIRED);
    }

    public static ExecutionContext current() {
        return CURRENT.get();
    }

    public static ExecutionContext attach(ExecutionContext context) {
        ExecutionContext previous = CURRENT.get();
        CURRENT.set(context);
        return previous;
    }

    public static void restore(ExecutionContext previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    public synchronized boolean beginWorker() {
        if (workerFinished || stopReason() != null) {
            workerFinished = true;
            releaseCapacityIfSettled();
            return false;
        }
        started = true;
        worker = Thread.currentThread();
        return true;
    }

    public synchronized boolean started() {
        return started;
    }

    public synchronized void finishWorker() {
        worker = null;
        workerFinished = true;
        releaseCapacityIfSettled();
    }

    public synchronized void opaqueBackend() {
        opaque = true;
    }

    public synchronized void managedBackend() {
        opaque = false;
    }

    public synchronized Lease track(Resource resource) {
        if (capacityReleased) {
            throw new IllegalStateException("execution resources have already settled");
        }
        active[resource.ordinal()]++;
        return new Lease(resource);
    }

    public synchronized void trackTemporary(java.nio.file.Path path) {
        temporaries.put(path, track(Resource.TEMP));
    }

    public synchronized void temporaryCleaned(java.nio.file.Path path, SandboxCleanupState state) {
        Lease lease = temporaries.remove(path);
        if (lease != null) {
            lease.complete(state);
        }
    }

    public final class Lease {
        private final Resource resource;
        private boolean closed;

        private Lease(Resource resource) {
            this.resource = resource;
        }

        public void complete(SandboxCleanupState state) {
            java.util.Objects.requireNonNull(state, "state");
            synchronized (ExecutionContext.this) {
                if (closed) {
                    return;
                }
                closed = true;
                int index = resource.ordinal();
                active[index]--;
                settled[index] = combine(settled[index], state);
                releaseCapacityIfSettled();
            }
        }
    }

    private void releaseCapacityIfSettled() {
        if (capacityReleased || !workerFinished) {
            return;
        }
        for (int count : active) {
            if (count != 0) {
                return;
            }
        }
        capacityReleased = true;
        whenSettled.run();
    }

    private static SandboxCleanupState combine(SandboxCleanupState old, SandboxCleanupState next) {
        if (old == SandboxCleanupState.FAILED || next == SandboxCleanupState.FAILED) {
            return SandboxCleanupState.FAILED;
        }
        if (old == SandboxCleanupState.UNKNOWN || next == SandboxCleanupState.UNKNOWN) {
            return SandboxCleanupState.UNKNOWN;
        }
        if (old == SandboxCleanupState.CONFIRMED || next == SandboxCleanupState.CONFIRMED) {
            return SandboxCleanupState.CONFIRMED;
        }
        return SandboxCleanupState.NOT_REQUIRED;
    }

    public synchronized SandboxCleanupResult cleanup() {
        return new SandboxCleanupResult(
                state(Resource.PROCESS),
                state(Resource.STREAM),
                state(Resource.TEMP),
                state(Resource.HOST),
                workerFinished);
    }

    public synchronized void reportCleanup(SandboxCleanupResult cleanup) {
        reportedCleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
    }

    private SandboxCleanupState state(Resource resource) {
        if ((opaque && reportedCleanup == null) || active[resource.ordinal()] > 0) {
            return SandboxCleanupState.UNKNOWN;
        }
        SandboxCleanupState state = settled[resource.ordinal()];
        if (reportedCleanup == null) {
            return state;
        }
        SandboxCleanupState declared;
        switch (resource) {
            case PROCESS:
                declared = reportedCleanup.processes();
                break;
            case STREAM:
                declared = reportedCleanup.streams();
                break;
            case TEMP:
                declared = reportedCleanup.temporaryFiles();
                break;
            default:
                declared = reportedCleanup.hostResources();
                break;
        }
        return combine(state, declared);
    }

    void onStop(Runnable listener) {
        stopListener = listener;
        if (stopped.get() != null) {
            listener.run();
        }
    }

    public boolean stop(SandboxTerminationReason reason) {
        boolean changed = stopped.compareAndSet(null, reason);
        if (changed) {
            beginCleanup();
            stopListener.run();
        }
        return changed;
    }

    public synchronized boolean cancel() {
        if (workerFinished) {
            return false;
        }
        if (deadline - System.nanoTime() <= 0) {
            stop(SandboxTerminationReason.TIMEOUT);
            return false;
        }
        boolean changed = stop(SandboxTerminationReason.CANCELLED);
        // Stop flags are polled by supervised backends. Never invoke an arbitrary
        // interruptible I/O hook while holding the deadline monitor or state lock.
        return changed;
    }

    public synchronized void expire() {
        if (workerFinished) {
            return;
        }
        stop(SandboxTerminationReason.TIMEOUT);
        // Stop flags are polled by supervised backends. Never invoke an arbitrary
        // interruptible I/O hook while holding the deadline monitor or state lock.
    }

    public synchronized boolean cancelBeforeStart() {
        if (worker != null || workerFinished) {
            return false;
        }
        workerFinished = true;
        releaseCapacityIfSettled();
        return true;
    }

    public SandboxTerminationReason stopReason() {
        if (deadline - System.nanoTime() <= 0) {
            stop(SandboxTerminationReason.TIMEOUT);
        }
        return stopped.get();
    }

    public long remainingNanos() {
        return Math.max(0, deadline - System.nanoTime());
    }

    public synchronized long beginCleanup() {
        if (cleanupDeadline == 0) {
            cleanupDeadline = Math.min(System.nanoTime() + graceNanos, deadline + graceNanos);
        }
        return cleanupDeadline;
    }

    public synchronized void endPhaseCleanup() {
        if (stopped.get() == null) {
            cleanupDeadline = 0;
        }
    }

    public long cleanupRemainingNanos() {
        return Math.max(0, beginCleanup() - System.nanoTime());
    }

    public long deliveryRemainingNanos() {
        return Math.max(0, deadline + graceNanos - System.nanoTime());
    }

    public String executionId() {
        return id;
    }

    public boolean cancellationRequested() {
        return stopped.get() == SandboxTerminationReason.CANCELLED;
    }

    public Duration remainingTime() {
        return Duration.ofNanos(remainingNanos());
    }

    public void checkActive() throws SandboxException {
        if (Thread.currentThread().isInterrupted()) {
            stop(SandboxTerminationReason.CANCELLED);
        }
        SandboxTerminationReason reason = stopReason();
        if (reason != null) {
            throw new SandboxException(
                    reason == SandboxTerminationReason.TIMEOUT
                            ? SandboxErrorCode.TIMEOUT
                            : reason == SandboxTerminationReason.OUTPUT_LIMIT
                                    ? SandboxErrorCode.OUTPUT_LIMIT
                                    : SandboxErrorCode.CANCELLED,
                    SandboxPhase.EXECUTION,
                    "execution stopped before next action");
        }
    }
}
