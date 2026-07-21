package io.github.sandboxdemo.api;

/** Host filesystem visibility requested for the sandboxed process tree. */
public enum ReadPolicy {
    /** Expose only system runtime paths plus declared readable/writable roots. */
    DECLARED_ONLY,

    /** Permit broad host reads while retaining write restrictions. */
    HOST
}
