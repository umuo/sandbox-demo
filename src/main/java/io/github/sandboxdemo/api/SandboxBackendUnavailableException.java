package io.github.sandboxdemo.api;

/** 后端不可用或策略不支持时抛出，禁止降级为普通宿主进程。 */
public final class SandboxBackendUnavailableException extends SandboxException {
    public SandboxBackendUnavailableException(String message) {
        this(SandboxErrorCode.BACKEND_UNAVAILABLE, SandboxPhase.PROBE, message);
    }

    public SandboxBackendUnavailableException(
            SandboxErrorCode code, SandboxPhase phase, String diagnostic) {
        super(code, phase, diagnostic);
    }
}
