package io.github.sandboxdemo.api;

import java.util.Objects;

/** Non-persistent runtime readiness result suitable for Agent health checks. */
public final class SandboxRuntimeStatus {

    private final SandboxCapabilities capabilities;
    private final boolean ready;
    private final String diagnostic;
    private final SandboxBackendInfo backendInfo;
    private final SandboxException error;

    public SandboxRuntimeStatus(
            SandboxCapabilities capabilities, boolean ready, String diagnostic) {
        this(
                capabilities,
                ready,
                diagnostic,
                SandboxBackendInfo.current(
                        capabilities.platform(), capabilities.backendName(), null),
                ready
                        ? null
                        : new SandboxException(
                                SandboxErrorCode.UNKNOWN, SandboxPhase.PROBE, diagnostic));
    }

    public SandboxRuntimeStatus(
            SandboxCapabilities capabilities,
            boolean ready,
            String diagnostic,
            SandboxBackendInfo backendInfo,
            SandboxException error) {
        if (ready && error != null) {
            throw new IllegalArgumentException("ready status cannot contain a failure");
        }
        this.backendInfo = Objects.requireNonNull(backendInfo, "backendInfo");
        this.error = error;
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.ready = ready;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public SandboxCapabilities capabilities() {
        return capabilities;
    }

    public boolean ready() {
        return ready;
    }

    public SandboxBackendInfo backendInfo() {
        return backendInfo;
    }

    public java.util.Optional<SandboxException> error() {
        return java.util.Optional.ofNullable(error);
    }

    /** Explicit diagnostic access; never put this field in default logs or product responses. */
    public String diagnostic() {
        return diagnostic;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SandboxRuntimeStatus)) {
            return false;
        }
        SandboxRuntimeStatus that = (SandboxRuntimeStatus) other;
        return ready == that.ready
                && capabilities.equals(that.capabilities)
                && diagnostic.equals(that.diagnostic)
                && backendInfo.equals(that.backendInfo)
                && java.util.Objects.equals(
                        error == null ? null : error.code(),
                        that.error == null ? null : that.error.code())
                && java.util.Objects.equals(
                        error == null ? null : error.phase(),
                        that.error == null ? null : that.error.phase());
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                capabilities,
                ready,
                diagnostic,
                backendInfo,
                error == null ? null : error.code(),
                error == null ? null : error.phase());
    }

    @Override
    public String toString() {
        return "SandboxRuntimeStatus[capabilities="
                + capabilities
                + ", ready="
                + ready
                + ", errorCode="
                + (error == null ? "none" : error.code())
                + ", diagnosticAvailable="
                + !diagnostic.isEmpty()
                + "]";
    }
}
