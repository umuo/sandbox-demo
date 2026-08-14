package io.github.sandboxdemo.api;

/** Controls deletion and rename within SDK-managed writable roots. */
public enum DeletionPolicy {
    /** Allow deletion within writable roots. */
    ALLOW,

    /** Deny deletion and rename, while retaining create and in-place write access. */
    DENY
}
