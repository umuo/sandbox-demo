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
    public String backendName() {
        return "windows-dedicated-user-restricted-token";
    }

    @Override
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {
        ValidatedPolicy policy = PathPolicyValidator.validate(request.policy());
        try {
            if (policy.readPolicy() != ReadPolicy.HOST) {
                throw new SandboxBackendUnavailableException(
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
            Path executable =
                    ExecutableResolver.resolve(
                            request.command().executable(),
                            policy.workingDirectory(),
                            environment,
                            policy.allowPathSearch());
            WindowsWorkerRuntime runtime = WindowsWorkerRuntime.resolve(installation, policy);

            try (WindowsPathLease ignored = WindowsPathLease.acquire(policy, executable);
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
                                request.command().arguments(),
                                request.standardInput(),
                                policy,
                                environment,
                                acl.capabilitySids());
                WindowsWorkerProtocol.writeRequest(session.requestFile(), workerRequest);
                session.sealRequest(credential.sid());
                return WindowsWorkerLauncher.execute(credential, runtime, session, policy);
            }
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    private static Path configuredHome() {
        String configured = System.getenv("SANDBOX_WINDOWS_HOME");
        return configured == null || io.github.sandboxdemo.core.Java8.isBlank(configured)
                ? WindowsSandboxInstallation.defaultHome()
                : java.nio.file.Paths.get(configured);
    }
}
