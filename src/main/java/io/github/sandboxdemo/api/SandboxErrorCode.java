package io.github.sandboxdemo.api;

/** 稳定错误分类；未迁移或自定义后端的错误使用 UNKNOWN，禁止匹配异常文本推断分类。 */
public enum SandboxErrorCode {
    UNKNOWN,
    BACKEND_UNAVAILABLE,
    POLICY_UNSUPPORTED,
    POLICY_INVALID,
    START_FAILED,
    OUTPUT_CAPTURE_FAILED,
    OUTPUT_LIMIT,
    TIMEOUT,
    CANCELLED,
    CLEANUP_FAILED,
    /** Host admission capacity is exhausted; no target or probe was launched. */
    EXECUTION_CAPACITY_EXCEEDED
}
