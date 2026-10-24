package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.SandboxErrorCode;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPhase;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared admission capacity for queued executions and SDK work that has not settled.
 *
 * <p>A delivered result does not release capacity while tracked pipe/callback tasks remain active.
 * This is a host admission bound, not proof of native cleanup or a kernel resource limit.
 */
public final class SandboxExecutionBudget {
    private final int limit;
    private final AtomicInteger inUse = new AtomicInteger();

    private SandboxExecutionBudget(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("execution budget must be positive");
        }
        this.limit = limit;
    }

    /** Creates a finite budget; share this same instance across clients in one admission domain. */
    public static SandboxExecutionBudget bounded(int limit) {
        return new SandboxExecutionBudget(limit);
    }

    /** Maximum number of outstanding SDK executions, including queued and unsettled work. */
    public int limit() {
        return limit;
    }

    /** Current reservations. This observation is not an atomic reservation or cleanup guarantee. */
    public int inUse() {
        return inUse.get();
    }

    Runnable reserve() throws SandboxException {
        while (true) {
            int current = inUse.get();
            if (current >= limit) {
                throw new SandboxException(
                        SandboxErrorCode.EXECUTION_CAPACITY_EXCEEDED,
                        SandboxPhase.ADMISSION,
                        "outstanding SDK execution budget exhausted");
            }
            if (inUse.compareAndSet(current, current + 1)) {
                AtomicBoolean returned = new AtomicBoolean();
                return () -> {
                    if (returned.compareAndSet(false, true)) {
                        inUse.decrementAndGet();
                    }
                };
            }
        }
    }

    @Override
    public String toString() {
        return "SandboxExecutionBudget[limit=" + limit + ", inUse=" + inUse() + "]";
    }
}
