package io.github.sandboxdemo.api;

/** Fixed, non-sensitive labels for host cleanup observations; no paths, handles or SIDs. */
public enum SandboxHostResource {
    NATIVE_HANDLES,
    NATIVE_ALLOCATIONS,
    PATH_HANDLES,
    FILESYSTEM_ACL,
    PRIVATE_DESKTOP,
    WINDOW_STATION_ACL,
    WORKER_SESSION,
    REMOTE_WORKER
}
