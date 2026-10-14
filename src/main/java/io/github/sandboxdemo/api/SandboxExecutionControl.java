package io.github.sandboxdemo.api;

import java.time.Duration;

/** Java 8 兼容的协作取消控制；剩余时间包含排队、准备及探针阶段。 */
public interface SandboxExecutionControl {
    String executionId();

    boolean cancellationRequested();

    Duration remainingTime();

    void checkActive() throws SandboxException;

    /** 扩展后端显式报告资源证据；后台执行是否结束仍由 SDK 判断。 默认不支持报告，不能据控制对象存在推断回收成功。 */
    default void reportCleanup(SandboxCleanupResult cleanup) {
        throw new UnsupportedOperationException("cleanup reporting is unavailable");
    }
}
