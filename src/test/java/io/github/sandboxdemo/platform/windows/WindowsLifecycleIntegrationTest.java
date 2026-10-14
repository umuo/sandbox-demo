package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** 专用账户后端的强制 Job 回收验收，不将异常转为跳过。 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SANDBOX_WINDOWS_PRODUCTION_TEST", matches = "1")
class WindowsLifecycleIntegrationTest {
    @TempDir Path workspace;

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(2),
                runnable -> {
                    Thread thread = new Thread(runnable, "windows-lifecycle-test");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private SandboxClient client() {
        return SandboxClient.builder()
                .windowsProductionHome(WindowsSandboxInstallation.defaultHome())
                .cleanupGrace(Duration.ofSeconds(2))
                .build();
    }

    private SandboxRequest request(Duration timeout, byte[] input) {
        Path cmd =
                Paths.get(
                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                        "System32",
                        "cmd.exe");
        // cmd does not consume stdin; ping is a bounded descendant holding inherited output pipes.
        return SandboxRequest.builder(workspace, cmd.toString())
                .arguments("/d", "/s", "/c", "echo ready>ready & ping -n 31 127.0.0.1 >nul")
                .readPolicy(ReadPolicy.HOST)
                .network(NetworkPolicy.ALLOW)
                .timeout(timeout)
                .standardInput(input)
                .build();
    }

    @Test
    void runningCancellationConfirmsOuterJobEmptyButDoesNotInventAclCleanupEvidence()
            throws Exception {
        java.util.logging.Logger logger =
                java.util.logging.Logger.getLogger(WindowsExecutionSupport.class.getName());
        java.util.logging.Level oldLevel = logger.getLevel();
        java.util.logging.ConsoleHandler diagnostics = new java.util.logging.ConsoleHandler();
        diagnostics.setLevel(java.util.logging.Level.FINE);
        logger.addHandler(diagnostics);
        logger.setLevel(java.util.logging.Level.FINE);
        ThreadPoolExecutor workers = pool();
        try {
            SandboxExecution execution =
                    client().start(request(Duration.ofSeconds(60), new byte[1024 * 1024]), workers);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            while (!Files.exists(workspace.resolve("ready")) && end - System.nanoTime() > 0) {
                Thread.sleep(20);
            }
            assertTrue(
                    Files.exists(workspace.resolve("ready")),
                    () ->
                            "synthetic command did not start: "
                                    + execution
                                            .completion()
                                            .map(
                                                    value ->
                                                            value.error()
                                                                    .map(
                                                                            error ->
                                                                                    error
                                                                                            .debugMessage())
                                                                    .orElse(value.toString()))
                                            .orElse("worker still preparing"));
            assertTrue(execution.cancel());
            assertEquals(
                    SandboxTerminationReason.CANCELLED,
                    execution.await(Duration.ofSeconds(4)).reason());
            workers.shutdown();
            assertTrue(workers.awaitTermination(4, TimeUnit.SECONDS));
            assertEquals(SandboxCleanupState.CONFIRMED, execution.cleanup().processes());
            assertFalse(execution.cleanup().confirmed());
            assertEquals(SandboxCleanupState.UNKNOWN, execution.cleanup().hostResources());
        } finally {
            workers.shutdownNow();
            logger.removeHandler(diagnostics);
            diagnostics.close();
            logger.setLevel(oldLevel);
        }
    }

    @Test
    void workerTimeoutReturnsWithinSharedDeadlineAndCleanupGrace() throws Exception {
        ThreadPoolExecutor workers = pool();
        try {
            long started = System.nanoTime();
            SandboxExecution execution =
                    client().start(request(Duration.ofMillis(800), new byte[1024 * 1024]), workers);
            SandboxCompletion completion = execution.await(Duration.ofSeconds(4));
            assertEquals(SandboxTerminationReason.TIMEOUT, completion.reason());
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(4))
                            < 0);
            assertFalse(completion.completeSuccessful());
        } finally {
            workers.shutdownNow();
        }
    }
}
