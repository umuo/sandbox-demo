package io.github.sandboxdemo.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Stable description of the policies an SDK backend can enforce. */
public final class SandboxCapabilities {

    private final SandboxPlatform platform;
    private final String backendName;
    private final Set<ReadPolicy> supportedReadPolicies;
    private final Set<NetworkPolicy> supportedNetworkPolicies;
    private final boolean installationRequired;

    public SandboxCapabilities(
            SandboxPlatform platform,
            String backendName,
            Set<ReadPolicy> supportedReadPolicies,
            Set<NetworkPolicy> supportedNetworkPolicies,
            boolean installationRequired) {
        this.platform = Objects.requireNonNull(platform, "platform");
        if (backendName == null || io.github.sandboxdemo.core.Java8.isBlank(backendName)) {
            throw new IllegalArgumentException("backendName must not be blank");
        }
        this.backendName = backendName;
        this.supportedReadPolicies =
                Collections.unmodifiableSet(new LinkedHashSet<>(supportedReadPolicies));
        this.supportedNetworkPolicies =
                Collections.unmodifiableSet(new LinkedHashSet<>(supportedNetworkPolicies));
        this.installationRequired = installationRequired;
    }

    public SandboxPlatform platform() {
        return platform;
    }

    public String backendName() {
        return backendName;
    }

    public Set<ReadPolicy> supportedReadPolicies() {
        return supportedReadPolicies;
    }

    public Set<NetworkPolicy> supportedNetworkPolicies() {
        return supportedNetworkPolicies;
    }

    public boolean installationRequired() {
        return installationRequired;
    }

    /** Whether this backend can enforce the requested read policy. */
    public boolean supports(ReadPolicy policy) {
        return supportedReadPolicies.contains(Objects.requireNonNull(policy, "policy"));
    }

    /** Whether this backend can enforce the requested network policy. */
    public boolean supports(NetworkPolicy policy) {
        return supportedNetworkPolicies.contains(Objects.requireNonNull(policy, "policy"));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SandboxCapabilities)) {
            return false;
        }
        SandboxCapabilities that = (SandboxCapabilities) other;
        return installationRequired == that.installationRequired
                && platform == that.platform
                && backendName.equals(that.backendName)
                && supportedReadPolicies.equals(that.supportedReadPolicies)
                && supportedNetworkPolicies.equals(that.supportedNetworkPolicies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                platform,
                backendName,
                supportedReadPolicies,
                supportedNetworkPolicies,
                installationRequired);
    }

    @Override
    public String toString() {
        return "SandboxCapabilities[platform="
                + platform
                + ", backendName="
                + backendName
                + ", supportedReadPolicies="
                + supportedReadPolicies
                + ", supportedNetworkPolicies="
                + supportedNetworkPolicies
                + ", installationRequired="
                + installationRequired
                + "]";
    }
}
