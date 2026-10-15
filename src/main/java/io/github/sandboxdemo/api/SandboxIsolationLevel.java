package io.github.sandboxdemo.api;

/** 隔离机制要求；OS_PROCESS 不代表全部资源受限，VM 也不自动证明某项限额。 */
public enum SandboxIsolationLevel {
    UNKNOWN,
    OS_PROCESS,
    VIRTUAL_MACHINE;

    public boolean satisfies(SandboxIsolationLevel required) {
        return required == UNKNOWN
                || this == required
                || (this == VIRTUAL_MACHINE && required == OS_PROCESS);
    }
}
