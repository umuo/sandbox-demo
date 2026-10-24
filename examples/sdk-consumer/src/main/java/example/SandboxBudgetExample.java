package example;

import io.github.sandboxdemo.api.SandboxErrorCode;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxExecution;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.sdk.SandboxClient;
import io.github.sandboxdemo.sdk.SandboxExecutionBudget;
import java.util.concurrent.ThreadPoolExecutor;

/** One admission domain shared by multiple clients; the host owns and bounds the worker pool. */
public final class SandboxBudgetExample {
    private final SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(8);
    private final SandboxClient foreground = SandboxClient.builder().executionBudget(budget).build();
    private final SandboxClient background = SandboxClient.builder().executionBudget(budget).build();

    public SandboxExecution start(SandboxRequest request, ThreadPoolExecutor workers, boolean foregroundRequest)
            throws SandboxException {
        try {
            return (foregroundRequest ? foreground : background).start(request, workers);
        } catch (SandboxException failure) {
            if (failure.code() == SandboxErrorCode.EXECUTION_CAPACITY_EXCEEDED) {
                // Signal backpressure to the caller. Do not discard the budget or retry without it.
                // No target or trusted probe has been started for this rejected request.
            }
            throw failure;
        }
    }

    public int outstandingSdkExecutions() {
        // Observation only: zero is not proof that every native cleanup dimension is confirmed.
        return budget.inUse();
    }
}
