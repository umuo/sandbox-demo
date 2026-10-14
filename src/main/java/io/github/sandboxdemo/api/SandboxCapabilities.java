package io.github.sandboxdemo.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable description of the policies an SDK backend can enforce. */
public final class SandboxCapabilities {

    private final Map<SandboxCapability, SandboxCapabilitySupport> dimensions;
    private final SandboxPlatform platform;
    private final String backendName;
    private final Set<ReadPolicy> supportedReadPolicies;
    private final Set<NetworkPolicy> supportedNetworkPolicies;
    private final Set<DeletionPolicy> supportedDeletionPolicies;
    private final boolean installationRequired;
    private final SandboxEnforcement enforcement;

    public SandboxCapabilities(
            SandboxPlatform platform,
            String backendName,
            Set<ReadPolicy> supportedReadPolicies,
            Set<NetworkPolicy> supportedNetworkPolicies,
            boolean installationRequired) {
        this(
                platform,
                backendName,
                supportedReadPolicies,
                supportedNetworkPolicies,
                Collections.singleton(DeletionPolicy.ALLOW),
                installationRequired,
                SandboxEnforcement.FULL);
    }

    public SandboxCapabilities(
            SandboxPlatform platform,
            String backendName,
            Set<ReadPolicy> supportedReadPolicies,
            Set<NetworkPolicy> supportedNetworkPolicies,
            boolean installationRequired,
            SandboxEnforcement enforcement) {
        this(
                platform,
                backendName,
                supportedReadPolicies,
                supportedNetworkPolicies,
                Collections.singleton(DeletionPolicy.ALLOW),
                installationRequired,
                enforcement);
    }

    public SandboxCapabilities(
            SandboxPlatform platform,
            String backendName,
            Set<ReadPolicy> supportedReadPolicies,
            Set<NetworkPolicy> supportedNetworkPolicies,
            Set<DeletionPolicy> supportedDeletionPolicies,
            boolean installationRequired,
            SandboxEnforcement enforcement) {
        this(
                platform,
                backendName,
                supportedReadPolicies,
                supportedNetworkPolicies,
                supportedDeletionPolicies,
                installationRequired,
                enforcement,
                Collections.emptyMap());
    }

    /** 新增维度描述；旧构造器及未指定维度均保守返回 UNKNOWN。 */
    public SandboxCapabilities(
            SandboxPlatform platform,
            String backendName,
            Set<ReadPolicy> supportedReadPolicies,
            Set<NetworkPolicy> supportedNetworkPolicies,
            Set<DeletionPolicy> supportedDeletionPolicies,
            boolean installationRequired,
            SandboxEnforcement enforcement,
            Map<SandboxCapability, SandboxCapabilitySupport> dimensions) {
        EnumMap<SandboxCapability, SandboxCapabilitySupport> copy =
                new EnumMap<>(SandboxCapability.class);
        for (SandboxCapability capability : SandboxCapability.values()) {
            copy.put(
                    capability,
                    new SandboxCapabilitySupport(SandboxGuarantee.UNKNOWN, "unspecified"));
        }
        Objects.requireNonNull(dimensions, "dimensions")
                .forEach(
                        (key, value) ->
                                copy.put(
                                        Objects.requireNonNull(key, "capability"),
                                        Objects.requireNonNull(value, "support")));
        this.dimensions = Collections.unmodifiableMap(copy);
        this.platform = Objects.requireNonNull(platform, "platform");
        if (backendName == null || io.github.sandboxdemo.core.Java8.isBlank(backendName)) {
            throw new IllegalArgumentException("backendName must not be blank");
        }
        this.backendName = backendName;
        this.supportedReadPolicies =
                Collections.unmodifiableSet(new LinkedHashSet<>(supportedReadPolicies));
        this.supportedNetworkPolicies =
                Collections.unmodifiableSet(new LinkedHashSet<>(supportedNetworkPolicies));
        this.supportedDeletionPolicies =
                Collections.unmodifiableSet(new LinkedHashSet<>(supportedDeletionPolicies));
        this.installationRequired = installationRequired;
        this.enforcement = Objects.requireNonNull(enforcement, "enforcement");
    }

    public Map<SandboxCapability, SandboxCapabilitySupport> dimensions() {
        return dimensions;
    }

    public SandboxCapabilitySupport support(SandboxCapability capability) {
        return dimensions.get(Objects.requireNonNull(capability, "capability"));
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

    public Set<DeletionPolicy> supportedDeletionPolicies() {
        return supportedDeletionPolicies;
    }

    public boolean installationRequired() {
        return installationRequired;
    }

    /** Returns whether the backend has documented enforcement gaps on this host platform. */
    public SandboxEnforcement enforcement() {
        return enforcement;
    }

    /** Whether this backend can enforce the requested read policy. */
    public boolean supports(ReadPolicy policy) {
        return supportedReadPolicies.contains(Objects.requireNonNull(policy, "policy"));
    }

    /** Whether this backend can enforce the requested network policy. */
    public boolean supports(NetworkPolicy policy) {
        return supportedNetworkPolicies.contains(Objects.requireNonNull(policy, "policy"));
    }

    /** Whether this backend can enforce the requested deletion policy. */
    public boolean supports(DeletionPolicy policy) {
        return supportedDeletionPolicies.contains(Objects.requireNonNull(policy, "policy"));
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
                && supportedNetworkPolicies.equals(that.supportedNetworkPolicies)
                && supportedDeletionPolicies.equals(that.supportedDeletionPolicies)
                && enforcement == that.enforcement
                && dimensions.equals(that.dimensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                platform,
                backendName,
                supportedReadPolicies,
                supportedNetworkPolicies,
                supportedDeletionPolicies,
                installationRequired,
                enforcement,
                dimensions);
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
                + ", supportedDeletionPolicies="
                + supportedDeletionPolicies
                + ", installationRequired="
                + installationRequired
                + ", dimensions="
                + dimensions
                + ", enforcement="
                + enforcement
                + "]";
    }
}
