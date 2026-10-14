package io.github.sandboxdemo.api;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** 有执行 ID 的取消句柄；取消请求、执行完成和资源回收互相独立。 */
public interface SandboxExecution {
    String executionId();

    boolean cancel();

    Optional<SandboxCompletion> completion();

    SandboxCompletion await() throws InterruptedException;

    SandboxCompletion await(Duration maximumWait) throws InterruptedException, TimeoutException;

    SandboxCleanupResult cleanup();
}
