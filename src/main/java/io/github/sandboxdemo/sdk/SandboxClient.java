package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.CommandSpec;
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
                        ? SandboxRuntime.defaultWindowsHome()
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

    /** Creates a client with the default platform backend and runtime location. */
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
        return runner.execute(Objects.requireNonNull(request, "request"));
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

        /** Overrides the Windows setup/runtime directory. Ignored on Linux and macOS. */
        public Builder windowsHome(Path windowsHome) {
            this.windowsHome = Objects.requireNonNull(windowsHome, "windowsHome");
            return this;
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
