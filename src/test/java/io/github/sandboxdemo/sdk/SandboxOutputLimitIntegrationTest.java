package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.MAC, OS.LINUX})
@EnabledIfEnvironmentVariable(named = "SANDBOX_RUN_OUTPUT_INTEGRATION", matches = "1")
class SandboxOutputLimitIntegrationTest {
    @TempDir Path workspace;

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1),
                runnable -> {
                    Thread t = new Thread(runnable, "output-limit-test");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private SandboxRequest.Builder request() {
        return request("while :; do printf synthetic-output; printf synthetic-error >&2; done");
    }

    private SandboxRequest.Builder request(String script) {
        return SandboxRequest.builder(workspace, "/bin/sh")
                .arguments("-c", script)
                .network(NetworkPolicy.DENY)
                .timeout(Duration.ofSeconds(10))
                .maxOutputBytes(1024)
                .maxCombinedOutputBytes(64)
                .onOutputLimit(OutputLimitAction.TERMINATE);
    }

    @Test
    void simultaneousInfiniteOutputTerminatesWithBoundedCapture() throws Exception {
        long started = System.nanoTime();
        SandboxResult result =
                SandboxClient.builder()
                        .cleanupGrace(Duration.ofMillis(500))
                        .build()
                        .execute(request().outputCallbackMode(OutputCallbackMode.RETAINED).build());
        assertTrue(result.outputLimitTerminated());
        assertFalse(result.timedOut());
        assertEquals(64, result.stdout().length + result.stderr().length);
        assertFalse(result.completeSuccessful());
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void blockedRawCallbackCannotDelayOutputLimitCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ThreadPoolExecutor workers = pool();
        try {
            SandboxRequest request =
                    request("while :; do printf synthetic-output; done")
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
                            .build();
            SandboxExecution execution =
                    SandboxClient.builder()
                            .cleanupGrace(Duration.ofMillis(500))
                            .build()
                            .start(request, workers);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            SandboxCompletion completion = execution.await(Duration.ofSeconds(2));
            assertEquals(SandboxTerminationReason.OUTPUT_LIMIT, completion.reason());
            assertEquals(SandboxErrorCode.OUTPUT_LIMIT, completion.error().get().code());
            assertFalse(completion.completeSuccessful());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().streams());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}
