package io.github.sandboxdemo.api;

import java.util.Objects;

/** Non-persistent runtime readiness result suitable for Agent health checks. */
public final class SandboxRuntimeStatus {

    private final SandboxCapabilities capabilities;
    private final boolean ready;
    private final String diagnostic;

    public SandboxRuntimeStatus(
            SandboxCapabilities capabilities, boolean ready, String diagnostic) {
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
                && diagnostic.equals(that.diagnostic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(capabilities, ready, diagnostic);
    }

    @Override
    public String toString() {
        return "SandboxRuntimeStatus[capabilities="
                + capabilities
                + ", ready="
                + ready
                + ", diagnostic="
                + diagnostic
                + "]";
    }
}
