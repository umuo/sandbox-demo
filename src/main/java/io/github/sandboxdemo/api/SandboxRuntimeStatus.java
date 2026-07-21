package io.github.sandboxdemo.api;

import java.util.Objects;

/** Non-persistent runtime readiness result suitable for Agent health checks. */
public record SandboxRuntimeStatus(
        SandboxCapabilities capabilities, boolean ready, String diagnostic) {

    public SandboxRuntimeStatus {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        diagnostic = Objects.requireNonNullElse(diagnostic, "");
    }
}
