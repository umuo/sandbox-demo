package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.core.EnvironmentPolicy;
import io.github.sandboxdemo.core.PathPolicyValidator;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.file.Path;
import java.util.Map;

/**
 * Production Windows strategy: dedicated online/offline user -> trusted worker -> write-restricted
 * token -> command Job Object.
 */
public final class WindowsProductionSandboxRunner implements SandboxRunner {

    private final Path home;

    public WindowsProductionSandboxRunner() {
        this(configuredHome());
    }

    public WindowsProductionSandboxRunner(Path home) {
        this.home = home.toAbsolutePath().normalize();
    }

    @Override
    public io.github.sandboxdemo.api.SandboxIsolationLevel isolationLevel() {
        return io.github.sandboxdemo.api.SandboxIsolationLevel.OS_PROCESS;
    }

    @Override
    public boolean supportsResourceLimits(io.github.sandboxdemo.api.SandboxResourceLimits limits) {
        return limits.cpuTimeMillis() == 0 && limits.diskBytes() == 0;
    }

    @Override
    public boolean supportsEnvironment(io.github.sandboxdemo.api.SandboxRequest request) {
        return true;
    }

    @Override
    public boolean supportsOutputPolicy(io.github.sandboxdemo.api.SandboxPolicy policy) {
        return true;
    }

    @Override
    public io.github.sandboxdemo.api.SandboxBackendInfo backendInfo() {
        return io.github.sandboxdemo.api.SandboxBackendInfo.current(
                io.github.sandboxdemo.api.SandboxPlatform.WINDOWS,
                backendName(),
                "windows-native; OS "
                        + System.getProperty("os.version")
                        + "; JNA "
                        + com.sun.jna.Native.VERSION
                        + "; worker-protocol "
                        + WindowsWorkerProtocol.VERSION);
    }

    @Override
    public String backendName() {
        return "windows-dedicated-user-restricted-token";
    }

    @Override
    public void validate(SandboxRequest request) throws SandboxException, InterruptedException {
        SandboxResult result = execute(request, true);
        if (!result.completeSuccessful()) {
            throw new SandboxException(
                    result.timedOut()
                            ? io.github.sandboxdemo.api.SandboxErrorCode.TIMEOUT
                            : io.github.sandboxdemo.api.SandboxErrorCode.BACKEND_UNAVAILABLE,
                    io.github.sandboxdemo.api.SandboxPhase.PROBE,
                    "Windows policy probe failed: " + result.stderrUtf8());
        }
    }

    @Override
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {
        return execute(request, false);
    }

    private SandboxResult execute(SandboxRequest request, boolean probe)
            throws SandboxException, InterruptedException {
        io.github.sandboxdemo.core.ExecutionContext context =
                io.github.sandboxdemo.core.ExecutionContext.current();
        if (context != null) {
            context.managedBackend();
            context.checkActive();
        }
        io.github.sandboxdemo.core.ResourceAdmission.validate(request.policy(), this);
        ValidatedPolicy policy = null;
        io.github.sandboxdemo.core.ExecutionContext.Lease preparation =
                context == null
                        ? null
                        : context.track(io.github.sandboxdemo.core.ExecutionContext.Resource.HOST);
        try {
            policy = PathPolicyValidator.validate(request);
            if (policy.readPolicy() != ReadPolicy.HOST) {
                throw new SandboxBackendUnavailableException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_UNSUPPORTED,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "Native Windows restricted tokens cannot provide a compatible "
                                + "allowlist-only read namespace. Select ReadPolicy.HOST for the "
                                + "workspace-write model, or run strict-confidential workloads in "
                                + "a disposable VM with only declared paths mounted.");
            }
            WindowsSandboxInstallation installation = WindowsSandboxInstallation.load(home);
            installation.verifyAccounts();
            WindowsSandboxInstallation.Credential credential =
                    policy.networkPolicy() == NetworkPolicy.DENY
                            ? installation.offline()
                            : installation.online();
            if (policy.networkPolicy() == NetworkPolicy.DENY) {
                WindowsFirewall.verify(credential.sid());
            }
            Map<String, String> environment = EnvironmentPolicy.build(request, policy);
            Path executable = EnvironmentPolicy.resolveExecutable(request, policy, environment);
            Path target = executable;
            if (probe) {
                executable =
                        java.nio.file.Paths.get(
                                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                                "System32",
                                "cmd.exe");
                environment = EnvironmentPolicy.probe(policy);
            }
            java.util.List<String> arguments =
                    probe
                            ? io.github.sandboxdemo.core.Java8.listOf("/d", "/s", "/c", "exit /b 0")
                            : request.command().arguments();
            byte[] input = probe ? new byte[0] : request.standardInput();
            ValidatedPolicy effective = probe ? policy.forProbe() : policy;
            java.util.List<Path> leasedTools =
                    probe
                            ? io.github.sandboxdemo.core.Java8.listOf(target, executable)
                            : java.util.Collections.singletonList(executable);

            WindowsWorkerRuntime runtime = WindowsWorkerRuntime.resolve(installation, policy);

            try (WindowsPathLease ignored = WindowsPathLease.acquire(policy, leasedTools);
                    WindowsAclManager.WindowsAclLease acl =
                            new WindowsAclManager(installation.home())
                                    .apply(
                                            policy,
                                            io.github.sandboxdemo.core.Java8.listOf(
                                                    credential.sid()));
                    WindowsSandboxSession session =
                            WindowsSandboxSession.create(installation.home(), credential.sid())) {
                WindowsWorkerProtocol.WorkerRequest workerRequest =
                        new WindowsWorkerProtocol.WorkerRequest(
                                credential.sid(),
                                executable,
                                arguments,
                                input,
                                effective,
                                environment,
                                acl.capabilitySids());
                WindowsWorkerProtocol.writeRequest(session.requestFile(), workerRequest);
                session.sealRequest(credential.sid());
                return WindowsWorkerLauncher.execute(
                        credential,
                        runtime,
                        session,
                        effective,
                        probe ? null : request.stdoutConsumer(),
                        probe ? null : request.stderrConsumer(),
                        probe ? null : request.stdoutTextConsumer(),
                        probe ? null : request.stderrTextConsumer(),
                        request.stdoutCharset(),
                        request.stderrCharset(),
                        request.stdoutCharsetAuto(),
                        request.stderrCharsetAuto());
            }
        } finally {
            try {
                if (policy != null) {
                    PathPolicyValidator.cleanup(policy);
                }
            } finally {
                if (preparation != null) {
                    preparation.complete(io.github.sandboxdemo.api.SandboxCleanupState.UNKNOWN);
                }
            }
        }
    }

    private static Path configuredHome() {
        String configured = System.getenv("SANDBOX_WINDOWS_HOME");
        return configured == null || io.github.sandboxdemo.core.Java8.isBlank(configured)
                ? WindowsSandboxInstallation.defaultHome()
                : java.nio.file.Paths.get(configured);
    }
}
