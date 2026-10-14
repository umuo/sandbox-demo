package io.github.sandboxdemo.api;

/** 回收证据状态；UNKNOWN 不能当作已释放资源。 */
public enum SandboxCleanupState {
    CONFIRMED,
    NOT_REQUIRED,
    UNKNOWN,
    FAILED
}
