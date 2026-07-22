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
 * Windows strategy using a synthetic capability SID, NTFS ACL, a WRITE_RESTRICTED token, and a
 * kill-on-close Job Object.
 */
public final class WindowsRestrictedTokenSandboxRunner implements SandboxRunner {

    @Override
    public String backendName() {
        return "windows-restricted-token";
    }

    @Override
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {

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
                        false);
            }
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }
}
