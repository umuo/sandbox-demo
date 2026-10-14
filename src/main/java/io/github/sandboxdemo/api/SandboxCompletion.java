package io.github.sandboxdemo.api;

import java.util.Objects;
import java.util.Optional;

/** 有界交付的执行结果。交付时的回收快照可能 UNKNOWN，应继续查询句柄。 */
public final class SandboxCompletion {
    private final String executionId;
    private final SandboxTerminationReason reason;
    private final SandboxResult result;
    private final SandboxException error;
    private final SandboxCleanupResult cleanup;

    public SandboxCompletion(
            String executionId,
            SandboxTerminationReason reason,
            SandboxResult result,
            SandboxException error,
            SandboxCleanupResult cleanup) {
        this.executionId = Objects.requireNonNull(executionId, "executionId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.result = result;
        this.error = error;
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
    }

    public String executionId() {
        return executionId;
    }

    public SandboxTerminationReason reason() {
        return reason;
    }

    public Optional<SandboxResult> result() {
        return Optional.ofNullable(result);
    }

    public Optional<SandboxException> error() {
        return Optional.ofNullable(error);
    }

    public SandboxCleanupResult cleanup() {
        return cleanup;
    }

    public boolean completeSuccessful() {
        return reason == SandboxTerminationReason.EXITED
                && error == null
                && result != null
                && result.completeSuccessful()
                && cleanup.confirmed();
    }

    @Override
    public String toString() {
        return "SandboxCompletion[id="
                + executionId
                + ", reason="
                + reason
                + ", result="
                + result
                + ", errorCode="
                + (error == null ? "none" : error.code())
                + ", cleanup="
                + cleanup
                + "]";
    }
}
