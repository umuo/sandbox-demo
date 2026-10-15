package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.*;

/** 在任何平台准备或目标启动前检查资源与隔离机制，旧扩展默认拒绝新增要求。 */
public final class ResourceAdmission {
    private ResourceAdmission() {}

    public static void validate(SandboxPolicy policy, SandboxRunner runner)
            throws SandboxException {
        if (!runner.supportsResourceLimits(policy.resourceLimits())
                || !runner.isolationLevel().satisfies(policy.requiredIsolation())) {
            throw new SandboxBackendUnavailableException(
                    SandboxErrorCode.POLICY_UNSUPPORTED,
                    SandboxPhase.ADMISSION,
                    "backend cannot enforce requested hard resources or isolation");
        }
    }
}
