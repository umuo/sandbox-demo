package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxCleanupResult;
import io.github.sandboxdemo.api.SandboxCleanupState;
import io.github.sandboxdemo.api.SandboxErrorCode;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxExecution;
import io.github.sandboxdemo.api.SandboxPhase;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxPolicyReadiness;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.core.DefaultSandboxRunnerFactory;
import io.github.sandboxdemo.core.ExecutionContext;
import io.github.sandboxdemo.core.ManagedExecution;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * Thread-safe facade used by an Agent to execute commands through the current OS sandbox.
 *
 * <p>The client never falls back to an unsandboxed process. A missing or incompatible backend is
 * reported as a checked {@link SandboxException}.
 */
public final class SandboxClient {

    private final Duration cleanupGrace;
    private final SandboxRunner runner;
    private final SandboxCapabilities capabilities;
    private final Supplier<SandboxRuntimeStatus> statusSupplier;

    private SandboxClient(Builder builder) {
        this.cleanupGrace = builder.cleanupGrace;
        SandboxPlatform platform = SandboxPlatform.current();
        Path windowsHome =
                builder.windowsHome == null
                        ? null
                        : builder.windowsHome.toAbsolutePath().normalize();
        if (builder.runner == null) {
            this.runner = DefaultSandboxRunnerFactory.create(windowsHome);
            this.capabilities = SandboxRuntime.capabilities(platform, this.runner.backendName());
            this.statusSupplier =
                    () -> SandboxRuntime.status(platform, windowsHome, this.runner.backendName());
        } else {
            this.runner = builder.runner;
            this.capabilities = builder.capabilities;
            this.statusSupplier = builder.statusSupplier;
        }
    }

    /** Creates a client with the setup-free default platform backend. */
    public static SandboxClient create() {
        return builder().build();
    }

    /** Starts a client builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Executes one request synchronously and supervises the complete process tree. */
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {
        SandboxRequest required = Objects.requireNonNull(request, "request");
        validateRequestCapabilities(required);
        ExecutionContext context = ExecutionContext.current();
        boolean owned = context == null;
        if (owned) {
            context = new ExecutionContext(required.policy().timeout(), cleanupGrace);
            context.beginWorker();
        }
        ExecutionContext previous = ExecutionContext.attach(context);
        try {
            context.checkActive();
            context.opaqueBackend();
            return runner.execute(required, context);
        } catch (LinkageError unavailable) {
            throw new SandboxException(
                    SandboxErrorCode.BACKEND_UNAVAILABLE,
                    SandboxPhase.PREPARATION,
                    "native backend linkage failed",
                    unavailable);
        } catch (RuntimeException unexpected) {
            throw new SandboxException(
                    SandboxErrorCode.UNKNOWN,
                    SandboxPhase.EXECUTION,
                    "backend runtime failure",
                    unexpected);
        } finally {
            if (owned) {
                context.finishWorker();
            }
            ExecutionContext.restore(previous);
        }
    }

    /** 在宿主有限工作池启动执行；要求有限队列与 AbortPolicy，禁止在提交线程执行工作。 */
    public SandboxExecution start(SandboxRequest request, ThreadPoolExecutor workers)
            throws SandboxException {
        SandboxRequest required = Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workers, "workers");
        if ((long) workers.getQueue().size() + workers.getQueue().remainingCapacity()
                        >= Integer.MAX_VALUE
                || !(workers.getRejectedExecutionHandler()
                        instanceof ThreadPoolExecutor.AbortPolicy)) {
            throw new IllegalArgumentException(
                    "execution pool requires a bounded queue and AbortPolicy");
        }
        validateRequestCapabilities(required);
        ExecutionContext context = new ExecutionContext(required.policy().timeout(), cleanupGrace);
        ManagedExecution execution = new ManagedExecution(context);
        execution.submit(() -> execute(required), workers);
        return execution;
    }

    /** 在预算提交前验证请求能力与实际策略。探针不执行目标命令；执行时仍重新预检。 */
    public void validate(SandboxRequest request) throws SandboxException, InterruptedException {
        SandboxRequest required = Objects.requireNonNull(request, "request");
        SandboxPolicyReadiness readiness = readiness(required);
        if (readiness.error().isPresent()) {
            throw readiness.error().get();
        }
    }

    /**
     * Runs only trusted probes with the requested policy; target argv/stdin/callbacks are not run.
     */
    public SandboxPolicyReadiness readiness(SandboxRequest request) throws InterruptedException {
        SandboxRequest required = Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        ExecutionContext context = ExecutionContext.current();
        boolean owned = context == null;
        if (owned) {
            context = new ExecutionContext(required.policy().timeout(), cleanupGrace);
            context.beginWorker();
        }
        ExecutionContext previous = ExecutionContext.attach(context);
        SandboxException error = null;
        io.github.sandboxdemo.api.SandboxBackendInfo info =
                io.github.sandboxdemo.api.SandboxBackendInfo.current(
                        capabilities.platform(), capabilities.backendName(), null);
        try {
            validateRequestCapabilities(required);
            context.checkActive();
            context.opaqueBackend();
            runner.validate(required);
            context.checkActive();
            info = Objects.requireNonNull(runner.backendInfo(), "backendInfo");
            if (info.platform() != capabilities.platform()
                    || !info.backendName().equals(capabilities.backendName())) {
                throw new IllegalArgumentException("backend metadata identity mismatch");
            }
            context.checkActive();
        } catch (SandboxException failure) {
            error =
                    (failure.phase() == SandboxPhase.EXECUTION
                                    || failure.phase() == SandboxPhase.START
                                    || failure.phase() == SandboxPhase.OUTPUT)
                            ? new SandboxException(
                                    failure.code(),
                                    SandboxPhase.PROBE,
                                    failure.debugMessage(),
                                    failure)
                            : failure;
        } catch (LinkageError | RuntimeException failure) {
            error =
                    new SandboxException(
                            SandboxErrorCode.BACKEND_UNAVAILABLE,
                            SandboxPhase.PROBE,
                            "readiness implementation failed",
                            failure);
        } finally {
            if (owned) {
                context.finishWorker();
            }
            ExecutionContext.restore(previous);
        }
        SandboxCleanupResult cleanup = context.cleanup();
        if (error == null
                && (cleanup.processes() == SandboxCleanupState.FAILED
                        || cleanup.streams() == SandboxCleanupState.FAILED
                        || cleanup.temporaryFiles() == SandboxCleanupState.FAILED
                        || cleanup.hostResources() == SandboxCleanupState.FAILED)) {
            error =
                    new SandboxException(
                            SandboxErrorCode.CLEANUP_FAILED,
                            SandboxPhase.CLEANUP,
                            "preflight cleanup failed");
        }
        return new SandboxPolicyReadiness(
                capabilities,
                info,
                required.policy(),
                error,
                cleanup,
                java.time.Instant.now(),
                Duration.ofNanos(System.nanoTime() - started));
    }

    private void validateRequestCapabilities(SandboxRequest request) throws SandboxException {
        io.github.sandboxdemo.core.EnvironmentPolicy.validate(request);
        boolean supported;
        try {
            supported = runner.supportsEnvironment(request);
        } catch (RuntimeException | LinkageError failure) {
            throw new SandboxException(
                    SandboxErrorCode.BACKEND_UNAVAILABLE,
                    SandboxPhase.ADMISSION,
                    "backend environment capability check failed",
                    failure);
        }
        if (!supported) {
            throw new SandboxBackendUnavailableException(
                    SandboxErrorCode.POLICY_UNSUPPORTED,
                    SandboxPhase.ADMISSION,
                    "backend does not support requested environment or temp policy");
        }
        validateCapabilities(request.policy());
    }

    private void validateCapabilities(SandboxPolicy policy) throws SandboxException {
        boolean supportsOutput;
        try {
            supportsOutput = runner.supportsOutputPolicy(policy);
        } catch (RuntimeException | LinkageError failure) {
            throw new SandboxException(
                    SandboxErrorCode.BACKEND_UNAVAILABLE,
                    SandboxPhase.ADMISSION,
                    "backend capability check failed",
                    failure);
        }
        if (!supportsOutput) {
            throw new SandboxBackendUnavailableException(
                    SandboxErrorCode.POLICY_UNSUPPORTED,
                    SandboxPhase.ADMISSION,
                    "backend does not support requested output policy");
        }
        if (!capabilities.supports(policy.readPolicy())) {
            throw new SandboxBackendUnavailableException(
                    SandboxErrorCode.POLICY_UNSUPPORTED,
                    SandboxPhase.ADMISSION,
                    "sandbox backend "
                            + capabilities.backendName()
                            + " does not support read policy "
                            + policy.readPolicy());
        }
        if (!capabilities.supports(policy.networkPolicy())) {
            throw new SandboxBackendUnavailableException(
                    SandboxErrorCode.POLICY_UNSUPPORTED,
                    SandboxPhase.ADMISSION,
                    "sandbox backend "
                            + capabilities.backendName()
                            + " does not support network policy "
                            + policy.networkPolicy());
        }
        if (!capabilities.supports(policy.deletionPolicy())) {
            throw new SandboxBackendUnavailableException(
                    SandboxErrorCode.POLICY_UNSUPPORTED,
                    SandboxPhase.ADMISSION,
                    "sandbox backend "
                            + capabilities.backendName()
                            + " does not support deletion policy "
                            + policy.deletionPolicy());
        }
    }

    /** Convenience overload for a request without additional environment variables. */
    public SandboxResult execute(SandboxPolicy policy, CommandSpec command)
            throws SandboxException, InterruptedException {
        return execute(SandboxRequest.of(policy, command));
    }

    /** Returns the selected backend name for logging and metrics. */
    public String backendName() {
        return runner.backendName();
    }

    /** Returns the current platform's static enforcement capabilities. */
    public SandboxCapabilities capabilities() {
        return capabilities;
    }

    /** Performs a non-persistent readiness probe of the configured platform runtime. */
    public SandboxRuntimeStatus status() {
        try {
            return Objects.requireNonNull(statusSupplier.get(), "runtime status");
        } catch (LinkageError | RuntimeException failure) {
            SandboxException error =
                    new SandboxException(
                            SandboxErrorCode.BACKEND_UNAVAILABLE,
                            SandboxPhase.PROBE,
                            "runtime status provider failed",
                            failure);
            return new SandboxRuntimeStatus(
                    capabilities,
                    false,
                    error.getMessage(),
                    io.github.sandboxdemo.api.SandboxBackendInfo.current(
                            capabilities.platform(), capabilities.backendName(), null),
                    error);
        }
    }

    /** Builder for SDK configuration. */
    public static final class Builder {

        private Duration cleanupGrace = Duration.ofSeconds(2);
        private Path windowsHome;
        private SandboxRunner runner;
        private SandboxCapabilities capabilities;
        private Supplier<SandboxRuntimeStatus> statusSupplier;

        private Builder() {}

        /** 设置统一清理宽限，范围为大于零至 30 秒。 */
        public Builder cleanupGrace(Duration grace) {
            Objects.requireNonNull(grace, "grace");
            if (grace.isNegative()
                    || grace.isZero()
                    || grace.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException(
                        "cleanup grace must be positive and at most 30 seconds");
            }
            this.cleanupGrace = grace;
            return this;
        }

        /**
         * Selects the opt-in dedicated-account Windows backend at this setup/runtime directory.
         * Ignored on Linux and macOS.
         */
        public Builder windowsHome(Path windowsHome) {
            this.windowsHome = Objects.requireNonNull(windowsHome, "windowsHome");
            return this;
        }

        /** Clearer alias for {@link #windowsHome(Path)}. */
        public Builder windowsProductionHome(Path windowsHome) {
            return windowsHome(windowsHome);
        }

        /**
         * Installs a custom strategy, primarily for embedding, tests, or a VM-backed
         * implementation.
         */
        public Builder backend(
                SandboxRunner runner,
                SandboxCapabilities capabilities,
                Supplier<SandboxRuntimeStatus> statusSupplier) {
            this.runner = Objects.requireNonNull(runner, "runner");
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            this.statusSupplier = Objects.requireNonNull(statusSupplier, "statusSupplier");
            if (!runner.backendName().equals(capabilities.backendName())) {
                throw new IllegalArgumentException(
                        "runner and capabilities must use the same backendName");
            }
            return this;
        }

        public SandboxClient build() {
            return new SandboxClient(this);
        }
    }
}
