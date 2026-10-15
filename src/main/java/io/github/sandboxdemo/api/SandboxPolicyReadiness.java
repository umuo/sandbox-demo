package io.github.sandboxdemo.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-request preflight snapshot; it is neither an execution permission nor a cleanup guarantee.
 */
public final class SandboxPolicyReadiness {
    private final SandboxCapabilities capabilities;
    private final SandboxBackendInfo backendInfo;
    private final SandboxException error;
    private final SandboxCleanupResult cleanup;
    private final Instant checkedAt;
    private final Duration duration;
    private final ReadPolicy readPolicy;
    private final NetworkPolicy networkPolicy;
    private final DeletionPolicy deletionPolicy;

    public SandboxPolicyReadiness(
            SandboxCapabilities capabilities,
            SandboxBackendInfo backendInfo,
            SandboxPolicy policy,
            SandboxException error,
            SandboxCleanupResult cleanup,
            Instant checkedAt,
            Duration duration) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.backendInfo = Objects.requireNonNull(backendInfo, "backendInfo");
        this.error = error;
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        this.duration = Objects.requireNonNull(duration, "duration");
        this.readPolicy = policy.readPolicy();
        this.networkPolicy = policy.networkPolicy();
        this.deletionPolicy = policy.deletionPolicy();
    }

    public boolean ready() {
        return error == null;
    }

    public SandboxCapabilities capabilities() {
        return capabilities;
    }

    public SandboxBackendInfo backendInfo() {
        return backendInfo;
    }

    public Optional<SandboxException> error() {
        return Optional.ofNullable(error);
    }

    public SandboxCleanupResult cleanup() {
        return cleanup;
    }

    public Instant checkedAt() {
        return checkedAt;
    }

    public Duration duration() {
        return duration;
    }

    public ReadPolicy readPolicy() {
        return readPolicy;
    }

    public NetworkPolicy networkPolicy() {
        return networkPolicy;
    }

    public DeletionPolicy deletionPolicy() {
        return deletionPolicy;
    }

    @Override
    public String toString() {
        return "SandboxPolicyReadiness[ready="
                + ready()
                + ", errorCode="
                + (error == null ? "none" : error.code())
                + ", errorPhase="
                + (error == null ? "none" : error.phase())
                + ", duration="
                + duration
                + ", cleanup="
                + cleanup
                + "]";
    }
}
