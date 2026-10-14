package io.github.sandboxdemo.api;

/** 错误发生阶段；UNKNOWN 表示后端尚未提供阶段证据。 */
public enum SandboxPhase {
    UNKNOWN,
    ADMISSION,
    PREPARATION,
    PROBE,
    START,
    EXECUTION,
    OUTPUT,
    CLEANUP
}
