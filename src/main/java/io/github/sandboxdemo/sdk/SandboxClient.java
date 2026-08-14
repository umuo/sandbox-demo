package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.core.DefaultSandboxRunnerFactory;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-safe facade used by an Agent to execute commands through the current OS sandbox.
 *
 * <p>The client never falls back to an unsandboxed process. A missing or incompatible backend is
 * reported as a checked {@link SandboxException}.
 */
public final class SandboxClient {

    private final SandboxRunner runner;
    private final SandboxCapabilities capabilities;
    private final Supplier<SandboxRuntimeStatus> statusSupplier;

    private SandboxClient(Builder builder) {
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
        SandboxPolicy policy = required.policy();
        if (!capabilities.supports(policy.readPolicy())) {
            throw new SandboxBackendUnavailableException(
                    "sandbox backend "
                            + capabilities.backendName()
                            + " does not support read policy "
                            + policy.readPolicy());
        }
        if (!capabilities.supports(policy.networkPolicy())) {
            throw new SandboxBackendUnavailableException(
                    "sandbox backend "
                            + capabilities.backendName()
                            + " does not support network policy "
                            + policy.networkPolicy());
        }
        if (!capabilities.supports(policy.deletionPolicy())) {
            throw new SandboxBackendUnavailableException(
                    "sandbox backend "
                            + capabilities.backendName()
                            + " does not support deletion policy "
                            + policy.deletionPolicy());
        }
        return runner.execute(required);
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
        return statusSupplier.get();
    }

    /** Builder for SDK configuration. */
    public static final class Builder {

        private Path windowsHome;
        private SandboxRunner runner;
        private SandboxCapabilities capabilities;
        private Supplier<SandboxRuntimeStatus> statusSupplier;

        private Builder() {}

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
