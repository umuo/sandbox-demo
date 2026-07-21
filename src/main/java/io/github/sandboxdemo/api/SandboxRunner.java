package io.github.sandboxdemo.api;

/** Strategy interface implemented by each operating-system backend. */
public interface SandboxRunner {

    String backendName();

    SandboxResult execute(SandboxRequest request) throws SandboxException, InterruptedException;
}
