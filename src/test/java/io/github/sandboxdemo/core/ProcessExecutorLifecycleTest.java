package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** 共享监督器测试使用宿主固定进程，不声称验证 OS 沙箱隔离。 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class ProcessExecutorLifecycleTest {
    @TempDir Path workspace;

    @Test
    void successfulChildMayCloseStdinWithoutConsumingIt() throws Exception {
        ExecutionContext context =
                new ExecutionContext(Duration.ofSeconds(2), Duration.ofMillis(300));
        context.beginWorker();
        context.managedBackend();
        ExecutionContext previous = ExecutionContext.attach(context);
        ValidatedPolicy policy =
                PathPolicyValidator.validate(SandboxPolicy.builder(workspace).build());
        try {
            SandboxResult result =
                    ProcessExecutor.execute(
                            java.util.Arrays.asList("/bin/sh", "-c", "exit 0"),
                            policy,
                            Collections.emptyMap(),
                            new byte[256]);
            assertTrue(result.successful());
            assertEquals(0, result.exitCode());
        } finally {
            PathPolicyValidator.cleanup(policy);
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
    }

    @Test
    void nonConsumingStdinAndChildHeldPipesRespectDeadlineAndGrace() throws Exception {
        ExecutionContext context =
                new ExecutionContext(Duration.ofMillis(150), Duration.ofMillis(300));
        context.beginWorker();
        context.managedBackend();
        ExecutionContext previous = ExecutionContext.attach(context);
        ValidatedPolicy policy =
                PathPolicyValidator.validate(
                        SandboxPolicy.builder(workspace).timeout(Duration.ofMillis(150)).build());
        long started = System.nanoTime();
        try {
            SandboxResult result =
                    ProcessExecutor.execute(
                            java.util.Arrays.asList("/bin/sh", "-c", "sleep 2 & wait"),
                            policy,
                            Collections.emptyMap(),
                            new byte[1024 * 1024]);
            assertTrue(result.timedOut());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1))
                            < 0);
            assertEquals(SandboxCleanupState.UNKNOWN, context.cleanup().processes());
        } finally {
            PathPolicyValidator.cleanup(policy);
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
        assertTrue(context.cleanup().workerFinished());
        assertEquals(SandboxCleanupState.CONFIRMED, context.cleanup().temporaryFiles());
    }

    @Test
    void blockedRawCallbackCannotBlockSupervisorAndIsNotReportedReclaimed() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackRelease = new CountDownLatch(1);
        ExecutionContext context =
                new ExecutionContext(Duration.ofSeconds(2), Duration.ofMillis(100));
        context.beginWorker();
        context.managedBackend();
        ExecutionContext previous = ExecutionContext.attach(context);
        ValidatedPolicy policy =
                PathPolicyValidator.validate(SandboxPolicy.builder(workspace).build());
        try {
            long started = System.nanoTime();
            ProcessExecutor.execute(
                    java.util.Arrays.asList("/bin/sh", "-c", "printf synthetic-output"),
                    policy,
                    Collections.emptyMap(),
                    new byte[0],
                    bytes -> {
                        callbackEntered.countDown();
                        boolean done = false;
                        while (!done) {
                            try {
                                callbackRelease.await();
                                done = true;
                            } catch (InterruptedException ignored) {
                            }
                        }
                    },
                    null);
            assertEquals(0, callbackEntered.getCount());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1))
                            < 0);
            assertEquals(SandboxCleanupState.UNKNOWN, context.cleanup().streams());
        } finally {
            callbackRelease.countDown();
            PathPolicyValidator.cleanup(policy);
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
    }
}
