package io.github.sandboxdemo.api;

/** Strategy interface implemented by each operating-system backend. */
public interface SandboxRunner {

    String backendName();

    /**
     * 使用可信探针验证实际策略，不启动调用方命令。旧后端默认拒绝此能力。
     *
     * <p>成功只表明当前探针可运行；不保证目标程序的全部运行时依赖或后续路径状态。
     */
    default void validate(SandboxRequest request) throws SandboxException, InterruptedException {
        throw new SandboxBackendUnavailableException(
                SandboxErrorCode.POLICY_UNSUPPORTED,
                SandboxPhase.PROBE,
                "backend does not implement per-request validation");
    }

    /** 旧 runner 默认仅在进入前检查控制信号，无法自动证明其内部资源回收。 */
    default SandboxResult execute(SandboxRequest request, SandboxExecutionControl control)
            throws SandboxException, InterruptedException {
        control.checkActive();
        return execute(request);
    }

    SandboxResult execute(SandboxRequest request) throws SandboxException, InterruptedException;
}
