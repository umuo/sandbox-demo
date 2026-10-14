package io.github.sandboxdemo.api;

/** 执行终止原因，与资源是否回收独立。 */
public enum SandboxTerminationReason {
    EXITED,
    TIMEOUT,
    CANCELLED,
    FAILED
}
