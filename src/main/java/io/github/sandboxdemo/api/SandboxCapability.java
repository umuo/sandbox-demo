package io.github.sandboxdemo.api;

/** 独立的强制能力维度；不等同于当前策略已通过原生预检。 */
public enum SandboxCapability {
    STRICT_READ,
    WRITE_ISOLATION,
    NETWORK_DENY,
    PROCESS_TREE_TERMINATION,
    MEMORY_LIMIT,
    PROCESS_LIMIT,
    CPU_LIMIT,
    DISK_LIMIT,
    TEMP_CLEANUP
}
