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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Windows strategy using a synthetic capability SID, NTFS ACL, a WRITE_RESTRICTED token, and a
 * kill-on-close Job Object.
 */
public final class WindowsRestrictedTokenSandboxRunner implements SandboxRunner {

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
                        + com.sun.jna.Native.VERSION);
    }

    @Override
    public String backendName() {
        return "windows-restricted-token";
    }

    /** Executes a benign restricted-token probe without persistent setup or caller code. */
    public static String probeBackend() throws SandboxException, InterruptedException {
        requireUnelevated();
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("agent-sandbox-windows-probe-");
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            String command = java.nio.file.Paths.get(systemRoot, "System32", "cmd.exe").toString();
            SandboxRequest request =
                    SandboxRequest.builder(workspace, command)
                            .arguments("/d", "/s", "/c", "exit 0")
                            .readOnlyWorkingDirectory()
                            .readPolicy(ReadPolicy.HOST)
                            .network(NetworkPolicy.ALLOW)
                            .timeout(Duration.ofSeconds(5))
                            .maxOutputBytes(64 * 1024)
                            .build();
            SandboxResult result = new WindowsRestrictedTokenSandboxRunner().execute(request);
            if (!result.successful()) {
                throw new SandboxBackendUnavailableException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_UNSUPPORTED,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "Windows restricted-token readiness probe failed (exit="
                                + result.exitCode()
                                + "): "
                                + result.stderrUtf8().trim());
            }
            return "setup-free Windows restricted-token probe passed";
        } catch (IOException e) {
            throw new SandboxBackendUnavailableException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_UNSUPPORTED,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "failed to create Windows readiness workspace: " + e.getMessage());
        } finally {
            if (workspace != null) {
                try {
                    Files.deleteIfExists(workspace);
                } catch (IOException ignored) {
                    // The empty readiness workspace is best-effort cleanup only.
                }
            }
        }
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

        requireUnelevated();
        io.github.sandboxdemo.core.ExecutionContext context =
                io.github.sandboxdemo.core.ExecutionContext.current();
        if (context != null) {
            context.managedBackend();
            context.checkActive();
        }
        io.github.sandboxdemo.core.ResourceAdmission.validate(request.policy(), this);
        ValidatedPolicy policy = PathPolicyValidator.validate(request);
        io.github.sandboxdemo.core.ExecutionContext.Lease preparation =
                context == null
                        ? null
                        : context.track(io.github.sandboxdemo.core.ExecutionContext.Resource.HOST);
        try {
            if (policy.readPolicy() != ReadPolicy.HOST) {
                throw new SandboxBackendUnavailableException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_UNSUPPORTED,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "Native Windows backends cannot enforce strict read isolation. "
                                + "A backend providing a separate read namespace is required.");
            }
            if (policy.networkPolicy() == NetworkPolicy.DENY) {
                throw new SandboxBackendUnavailableException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_UNSUPPORTED,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "This unelevated Windows backend enforces file writes and process lifetime, "
                                + "but cannot enforce network deny. Request NetworkPolicy.ALLOW or add "
                                + "a dedicated offline user plus Windows Firewall setup.");
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

            try (WindowsPathLease ignored = WindowsPathLease.acquire(policy, leasedTools);
                    WindowsAclManager.WindowsAclLease acl = new WindowsAclManager().apply(policy)) {
                return WindowsRestrictedProcessLauncher.execute(
                        executable,
                        arguments,
                        input,
                        effective,
                        environment,
                        acl.capabilitySids(),
                        false,
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
                PathPolicyValidator.cleanup(policy);
            } finally {
                if (preparation != null) {
                    preparation.complete(io.github.sandboxdemo.api.SandboxCleanupState.UNKNOWN);
                }
            }
        }
    }

    private static void requireUnelevated() throws SandboxBackendUnavailableException {
        if (com.sun.jna.platform.win32.Advapi32Util.isCurrentProcessElevated()) {
            throw new SandboxBackendUnavailableException(
                    io.github.sandboxdemo.api.SandboxErrorCode.BACKEND_UNAVAILABLE,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "the setup-free Windows sandbox refuses to run from an elevated process; "
                            + "restart the Agent normally without Administrator elevation");
        }
    }
}
