package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.OutputLimitAction;
import io.github.sandboxdemo.api.SandboxTerminationReason;

/** Shared, thread-safe retained-byte budget for one target's stdout and stderr. */
public final class OutputBudget {
    private final int combinedLimit;
    private final boolean terminate;
    private final ExecutionContext context;
    private int retained;
    private boolean exceeded;

    public OutputBudget(ValidatedPolicy policy, ExecutionContext context) {
        this(policy.maxCombinedOutputBytes(), policy.onOutputLimit(), context);
    }

    public OutputBudget(int combinedLimit, OutputLimitAction action, ExecutionContext context) {
        if (combinedLimit < 0 || combinedLimit > 128 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid combined output budget");
        }
        java.util.Objects.requireNonNull(action, "action");
        if (action == OutputLimitAction.TERMINATE && context == null) {
            throw new IllegalArgumentException("termination needs execution control");
        }
        this.combinedLimit = combinedLimit;
        this.terminate = action == OutputLimitAction.TERMINATE;
        this.context = context;
    }

    synchronized int retain(int streamRemaining, int count) {
        int allowed = Math.min(streamRemaining, count);
        if (combinedLimit != 0) {
            allowed = Math.min(allowed, combinedLimit - retained);
            retained += allowed;
        }
        if (allowed < count) {
            exceeded = true;
            if (terminate && context != null) {
                context.stop(SandboxTerminationReason.OUTPUT_LIMIT);
            }
        }
        return allowed;
    }

    public synchronized boolean shouldStopReading() {
        return terminate && exceeded;
    }
}
