package io.github.sandboxdemo.api;

/** Base checked exception for sandbox setup and execution failures. */
public class SandboxException extends Exception {

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
