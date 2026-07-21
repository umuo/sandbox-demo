package io.github.sandboxdemo.api;

import java.util.Objects;
import java.util.Set;

/** Stable description of the policies an SDK backend can enforce. */
public record SandboxCapabilities(
        SandboxPlatform platform,
        String backendName,
        Set<ReadPolicy> supportedReadPolicies,
        Set<NetworkPolicy> supportedNetworkPolicies,
        boolean installationRequired) {

    public SandboxCapabilities {
        platform = Objects.requireNonNull(platform, "platform");
        if (backendName == null || backendName.isBlank()) {
            throw new IllegalArgumentException("backendName must not be blank");
        }
        supportedReadPolicies = Set.copyOf(supportedReadPolicies);
        supportedNetworkPolicies = Set.copyOf(supportedNetworkPolicies);
    }

    /** Whether this backend can enforce the requested read policy. */
    public boolean supports(ReadPolicy policy) {
        return supportedReadPolicies.contains(Objects.requireNonNull(policy, "policy"));
    }

    /** Whether this backend can enforce the requested network policy. */
    public boolean supports(NetworkPolicy policy) {
        return supportedNetworkPolicies.contains(Objects.requireNonNull(policy, "policy"));
    }
}
