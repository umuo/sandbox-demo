package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import io.github.sandboxdemo.sdk.SandboxExecutionBudget;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIf("io.github.sandboxdemo.platform.windows.WindowsNativeTestSupport#enabled")
class WindowsOutputLimitIntegrationTest {
    @TempDir Path workspace;

    private void admissionEvidence(
            String phase,
            SandboxClient client,
            SandboxExecutionBudget budget,
            ThreadPoolExecutor workers)
            throws Exception {
        com.sun.jna.ptr.IntByReference handles = new com.sun.jna.ptr.IntByReference();
        int ok =
                com.sun.jna.NativeLibrary.getInstance("kernel32")
                        .getFunction("GetProcessHandleCount", com.sun.jna.Function.ALT_CONVENTION)
                        .invokeInt(
                                new Object[] {
                                    com.sun.jna.platform.win32.Kernel32.INSTANCE
                                            .GetCurrentProcess(),
                                    handles
                                });
        assertNotEquals(0, ok, "could not observe native handle count");
        String json =
                "{\"budgetLimit\":"
                        + budget.limit()
                        + ",\"budgetInUse\":"
                        + budget.inUse()
                        + ",\"processHandles\":"
                        + handles.getValue()
                        + ",\"jvmThreads\":"
                        + java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount()
                        + ",\"poolActive\":"
                        + workers.getActiveCount()
                        + ",\"poolQueued\":"
                        + workers.getQueue().size()
                        + "}";
        Path directory = Paths.get("target", "admission-evidence");
        Files.createDirectories(directory);
        Files.write(
                directory.resolve(client.backendName() + "-" + phase + ".json"),
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

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
        return WindowsNativeTestSupport.client();
    }

    @Test
    void preservesOutputLimitAndCombinedRetainedCallbacks() throws Exception {
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
    void blockedHostCallbackDoesNotEraseOutputLimitReason() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ThreadPoolExecutor workers = pool();
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        SandboxClient client = WindowsNativeTestSupport.client(budget);
        try {
            admissionEvidence("before", client, budget, workers);
            SandboxExecution execution =
                    client.start(
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
            assertEquals(1, budget.inUse());
            for (int i = 0; i < 20; i++) {
                SandboxException denied =
                        assertThrows(
                                SandboxException.class,
                                () ->
                                        client.start(
                                                request("echo unexpected>budget-marker").build(),
                                                workers));
                assertEquals(SandboxErrorCode.EXECUTION_CAPACITY_EXCEEDED, denied.code());
                assertEquals(SandboxPhase.ADMISSION, denied.phase());
            }
            assertFalse(Files.exists(workspace.resolve("budget-marker")));
            admissionEvidence("saturated", client, budget, workers);
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (budget.inUse() != 0 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(0, budget.inUse());
            SandboxResult recovered = client.execute(request("echo recovered").build());
            assertTrue(recovered.completeSuccessful(), recovered.toString());
            assertEquals(0, budget.inUse());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().streams());
            admissionEvidence("recovered", client, budget, workers);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
