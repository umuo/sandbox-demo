package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** 支持宿主的强制原生验收；后端异常直接失败，不转换为跳过。 */
@EnabledOnOs({OS.MAC, OS.LINUX})
@EnabledIfEnvironmentVariable(named = "SANDBOX_RUN_LIFECYCLE_INTEGRATION", matches = "1")
class SandboxNativeLifecycleTest {
    @TempDir Path workspace;

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(2),
                runnable -> {
                    Thread thread = new Thread(runnable, "native-lifecycle-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private SandboxClient client() {
        return SandboxClient.builder().cleanupGrace(Duration.ofMillis(400)).build();
    }

    private SandboxRequest request(String script, Duration timeout) {
        return SandboxRequest.builder(workspace, "/bin/sh")
                .arguments("-c", script)
                .network(NetworkPolicy.DENY)
                .timeout(timeout)
                .build();
    }

    private void awaitMarker(Path marker) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(marker) && end - System.nanoTime() > 0) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(marker), "synthetic target did not start");
    }

    private void assertProcessGuarantee(SandboxExecution execution) {
        assertEquals(
                SandboxPlatform.current() == SandboxPlatform.MACOS
                        ? SandboxCleanupState.UNKNOWN
                        : SandboxCleanupState.CONFIRMED,
                execution.cleanup().processes());
    }

    @Test
    void cancellationDuringTargetRuntimeReclaimsSupervisedProcessWithinGrace() throws Exception {
        ThreadPoolExecutor workers = pool();
        try {
            SandboxExecution execution =
                    client().start(
                                    request(
                                            "echo $$ > rootpid; sleep 2 & echo $! > childpid; printf ready > ready; wait",
                                            Duration.ofSeconds(5)),
                                    workers);
            awaitMarker(workspace.resolve("ready"));
            long started = System.nanoTime();
            assertTrue(execution.cancel());
            SandboxCompletion completion = execution.await(Duration.ofSeconds(2));
            assertEquals(SandboxTerminationReason.CANCELLED, completion.reason());
            assertEquals(SandboxErrorCode.CANCELLED, completion.error().get().code());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1))
                            < 0);
            workers.shutdown();
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS));
            assertProcessGuarantee(execution);
            assertEquals(SandboxCleanupState.CONFIRMED, execution.cleanup().temporaryFiles());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void nonConsumingStdinAndBackgroundOutputAreBounded() throws Exception {
        ThreadPoolExecutor workers = pool();
        try {
            SandboxRequest request =
                    SandboxRequest.builder(workspace, "/bin/sh")
                            .arguments("-c", "sleep 2 & wait")
                            .standardInput(new byte[1024 * 1024])
                            .network(NetworkPolicy.DENY)
                            .timeout(Duration.ofMillis(500))
                            .build();
            long started = System.nanoTime();
            SandboxExecution execution = client().start(request, workers);
            SandboxCompletion completion = execution.await(Duration.ofSeconds(2));
            assertEquals(SandboxTerminationReason.TIMEOUT, completion.reason());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2))
                            < 0);
            workers.shutdown();
            assertTrue(workers.awaitTermination(1, TimeUnit.SECONDS));
            assertProcessGuarantee(execution);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void blockedCallbackHasIndependentCleanupAndNoCompleteSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor workers = pool();
        try {
            SandboxRequest request =
                    SandboxRequest.builder(workspace, "/bin/sh")
                            .arguments("-c", "printf synthetic-output")
                            .network(NetworkPolicy.DENY)
                            .timeout(Duration.ofSeconds(3))
                            .stdoutConsumer(
                                    bytes -> {
                                        entered.countDown();
                                        boolean done = false;
                                        while (!done) {
                                            try {
                                                release.await();
                                                done = true;
                                            } catch (InterruptedException ignored) {
                                            }
                                        }
                                    })
                            .build();
            SandboxExecution execution = client().start(request, workers);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            SandboxCompletion completion = execution.await(Duration.ofSeconds(2));
            assertFalse(completion.completeSuccessful());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().streams());
            release.countDown();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (execution.cleanup().streams() == SandboxCleanupState.UNKNOWN
                    && end - System.nanoTime() > 0) {
                Thread.sleep(10);
            }
            assertEquals(SandboxCleanupState.CONFIRMED, execution.cleanup().streams());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().streams());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
}
