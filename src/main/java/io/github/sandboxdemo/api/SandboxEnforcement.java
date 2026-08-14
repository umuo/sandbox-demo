package io.github.sandboxdemo.api;

/** How completely a backend enforces its advertised sandbox policy on the current platform. */
public enum SandboxEnforcement {
    /** The backend enforces all advertised policy properties within the documented threat model. */
    FULL,

    /** The backend enforces the policy with documented host-platform gaps. */
    PARTIAL
}
