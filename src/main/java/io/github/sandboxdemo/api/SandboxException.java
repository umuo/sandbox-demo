package io.github.sandboxdemo.api;

import java.util.Objects;

/**
 * 沙箱失败的稳定公开契约。默认消息和异常链不包含诊断原文。
 *
 * <p>历史构造器继续可用，但分类为 UNKNOWN；详细信息仅通过显式调试接口读取。
 */
public class SandboxException extends Exception {
    private final SandboxErrorCode code;
    private final SandboxPhase phase;
    private final String debugMessage;
    private final Throwable debugCause;

    public SandboxException(String message) {
        this(SandboxErrorCode.UNKNOWN, SandboxPhase.UNKNOWN, message, null);
    }

    public SandboxException(String message, Throwable cause) {
        this(SandboxErrorCode.UNKNOWN, SandboxPhase.UNKNOWN, message, cause);
    }

    public SandboxException(SandboxErrorCode code, SandboxPhase phase, String diagnostic) {
        this(code, phase, diagnostic, null);
    }

    public SandboxException(
            SandboxErrorCode code, SandboxPhase phase, String diagnostic, Throwable cause) {
        super(
                "Sandbox failure: "
                        + Objects.requireNonNull(code, "code")
                        + " (phase="
                        + Objects.requireNonNull(phase, "phase")
                        + ")",
                null);
        this.code = code;
        this.phase = phase;
        this.debugMessage = diagnostic == null ? "" : diagnostic;
        this.debugCause = cause;
    }

    public SandboxErrorCode code() {
        return code;
    }

    public SandboxPhase phase() {
        return phase;
    }

    /** 可能含输出、路径和环境信息，仅用于显式受控调试。 */
    public String debugMessage() {
        return debugMessage;
    }

    /** 原始异常可能含敏感信息，不应写入默认日志或产品响应。 */
    public Throwable debugCause() {
        return debugCause;
    }
}
