package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SANDBOX_WINDOWS_PRODUCTION_TEST", matches = "1")
class WindowsOutputLimitIntegrationTest {
    @TempDir Path workspace;

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1),
                runnable -> {
                    Thread t = new Thread(runnable, "windows-output-limit-test");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private SandboxRequest.Builder request() {
        return request(
                "for /l %i in (1,1,100000) do @(echo synthetic-output & echo synthetic-error 1>&2)");
    }

    private SandboxRequest.Builder request(String script) {
        Path cmd =
                Paths.get(
                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                        "System32",
                        "cmd.exe");
        // Finite fallback protects the CI host if output termination regresses.
        return SandboxRequest.builder(workspace, cmd.toString())
                .arguments("/d", "/s", "/c", script)
                .readPolicy(ReadPolicy.HOST)
                .network(NetworkPolicy.ALLOW)
                .timeout(Duration.ofSeconds(120))
                .maxOutputBytes(1024)
                .maxCombinedOutputBytes(64)
                .onOutputLimit(OutputLimitAction.TERMINATE);
    }

    private SandboxClient client() {
        return SandboxClient.builder()
                .windowsProductionHome(WindowsSandboxInstallation.defaultHome())
                .cleanupGrace(Duration.ofSeconds(2))
                .build();
    }

    @Test
    void workerProtocolPreservesOutputLimitAndCombinedRetainedCallbacks() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        ThreadPoolExecutor workers = pool();
        try {
            SandboxExecution execution =
                    client().start(
                                    request()
                                            .outputCallbackMode(OutputCallbackMode.RETAINED)
                                            .stdoutConsumer(
                                                    bytes -> observed.addAndGet(bytes.length))
                                            .stderrConsumer(
                                                    bytes -> observed.addAndGet(bytes.length))
                                            .build(),
                                    workers);
            SandboxCompletion completion = execution.await(Duration.ofSeconds(90));
            assertEquals(
                    SandboxTerminationReason.OUTPUT_LIMIT,
                    completion.reason(),
                    completion.toString());
            assertEquals(SandboxErrorCode.OUTPUT_LIMIT, completion.error().get().code());
            assertFalse(completion.completeSuccessful());
            assertTrue(observed.get() <= 64);
            if (completion.result().isPresent()) {
                SandboxResult result = completion.result().get();
                assertTrue(result.outputLimitTerminated());
                assertFalse(result.timedOut());
                assertEquals(64, result.stdout().length + result.stderr().length);
            }
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(SandboxCleanupState.CONFIRMED, execution.cleanup().processes());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void blockedHostCallbackDoesNotEraseWorkerOutputLimitReason() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ThreadPoolExecutor workers = pool();
        try {
            SandboxExecution execution =
                    client().start(
                                    request("for /l %i in (1,1,100000) do @echo synthetic-output")
                                            .maxCombinedOutputBytes(1)
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
                                            .build(),
                                    workers);
            assertTrue(entered.await(90, TimeUnit.SECONDS));
            SandboxCompletion completion = execution.await(Duration.ofSeconds(8));
            assertEquals(
                    SandboxTerminationReason.OUTPUT_LIMIT,
                    completion.reason(),
                    completion.toString());
            assertEquals(SandboxErrorCode.OUTPUT_LIMIT, completion.error().get().code());
            assertFalse(completion.completeSuccessful());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().streams());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
