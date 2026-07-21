package io.github.sandboxdemo.api;

/** Raised instead of silently running a command without OS sandboxing. */
public final class SandboxBackendUnavailableException extends SandboxException {

    public SandboxBackendUnavailableException(String message) {
        super(message);
    }
}
