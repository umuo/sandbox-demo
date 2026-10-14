package io.github.sandboxdemo.api;

/** 能力保证级别。KERNEL 仅描述强制机制，不代表 VM 隔离或本机验收已通过。 */
public enum SandboxGuarantee {
    KERNEL,
    BEST_EFFORT,
    UNSUPPORTED,
    UNKNOWN
}
