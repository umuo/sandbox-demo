package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.core.EnvironmentPolicy;
import io.github.sandboxdemo.core.ExecutableResolver;
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
                        "Windows restricted-token readiness probe failed (exit="
                                + result.exitCode()
                                + "): "
                                + result.stderrUtf8().trim());
            }
            return "setup-free Windows restricted-token probe passed";
        } catch (IOException e) {
            throw new SandboxBackendUnavailableException(
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
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {

        requireUnelevated();
        ValidatedPolicy policy = PathPolicyValidator.validate(request.policy());
        try {
            if (policy.readPolicy() != ReadPolicy.HOST) {
                throw new SandboxBackendUnavailableException(
                        "The unelevated Windows backend cannot enforce strict read isolation. "
                                + "Use the production dedicated-user backend or explicitly request "
                                + "ReadPolicy.HOST for development-only use.");
            }
            if (policy.networkPolicy() == NetworkPolicy.DENY) {
                throw new SandboxBackendUnavailableException(
                        "This unelevated Windows backend enforces file writes and process lifetime, "
                                + "but cannot enforce network deny. Request NetworkPolicy.ALLOW or add "
                                + "a dedicated offline user plus Windows Firewall setup.");
            }

            Map<String, String> environment = EnvironmentPolicy.build(request, policy);
            Path executable =
                    ExecutableResolver.resolve(
                            request.command().executable(),
                            policy.workingDirectory(),
                            environment,
                            policy.allowPathSearch());
            try (WindowsPathLease ignored = WindowsPathLease.acquire(policy, executable);
                    WindowsAclManager.WindowsAclLease acl = new WindowsAclManager().apply(policy)) {
                return WindowsRestrictedProcessLauncher.execute(
                        executable,
                        request.command().arguments(),
                        request.standardInput(),
                        policy,
                        environment,
                        acl.capabilitySids(),
                        false,
                        request.stdoutConsumer(),
                        request.stderrConsumer(),
                        request.stdoutTextConsumer(),
                        request.stderrTextConsumer(),
                        request.stdoutCharset(),
                        request.stderrCharset(),
                        request.stdoutCharsetAuto(),
                        request.stderrCharsetAuto());
            }
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    private static void requireUnelevated() throws SandboxBackendUnavailableException {
        if (com.sun.jna.platform.win32.Advapi32Util.isCurrentProcessElevated()) {
            throw new SandboxBackendUnavailableException(
                    "the setup-free Windows sandbox refuses to run from an elevated process; "
                            + "restart the Agent normally without Administrator elevation");
        }
    }
}
