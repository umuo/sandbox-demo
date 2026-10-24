package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** Real shared-supervisor pipe threads; this fixture does not claim OS sandbox isolation. */
@EnabledOnOs({OS.MAC, OS.LINUX})
class SandboxCallbackAccumulationTest {
    @TempDir Path workspace;

    private SandboxClient.Builder builder() {
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "synthetic-pipe-supervisor";
                    }

                    public SandboxResult execute(SandboxRequest request)
                            throws SandboxException, InterruptedException {
                        ExecutionContext.current().managedBackend();
                        ValidatedPolicy policy = PathPolicyValidator.validate(request);
                        try {
                            return ProcessExecutor.execute(
                                    Arrays.asList("/bin/sh", "-c", "printf synthetic"),
                                    policy,
                                    Collections.emptyMap(),
                                    new byte[0],
                                    request.stdoutConsumer(),
                                    null);
                        } finally {
                            PathPolicyValidator.cleanup(policy);
                        }
                    }
                };
        SandboxCapabilities caps =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        runner.backendName(),
                        Collections.singleton(ReadPolicy.HOST),
                        Collections.singleton(NetworkPolicy.ALLOW),
                        false);
        return SandboxClient.builder()
                .cleanupGrace(Duration.ofMillis(150))
                .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, "synthetic"));
    }

    private ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private SandboxRequest blocked(
            CountDownLatch release, AtomicInteger live, CountDownLatch entered) {
        return SandboxRequest.builder(workspace, "/bin/sh")
                .readPolicy(ReadPolicy.HOST)
                .network(NetworkPolicy.ALLOW)
                .timeout(Duration.ofSeconds(10))
                .stdoutConsumer(
                        bytes -> {
                            live.incrementAndGet();
                            entered.countDown();
                            try {
                                boolean done = false;
                                while (!done) {
                                    try {
                                        release.await();
                                        done = true;
                                    } catch (InterruptedException ignored) {
                                        /* deliberately uncooperative */
                                    }
                                }
                            } finally {
                                live.decrementAndGet();
                            }
                        })
                .build();
    }

    private void awaitStreams(List<SandboxExecution> executions) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean finished = true;
            for (SandboxExecution execution : executions) {
                finished &= execution.cleanup().streams() == SandboxCleanupState.CONFIRMED;
            }
            if (finished) {
                return;
            }
            Thread.sleep(10);
        }
        fail("synthetic pipe callbacks did not release their tracked streams");
    }

    private void evidence(String name, int live, ThreadPoolExecutor workers) throws Exception {
        evidence(name, live, workers, null);
    }

    private void evidence(
            String name, int live, ThreadPoolExecutor workers, SandboxExecutionBudget budget)
            throws Exception {
        long openFiles = -1;
        try {
            Class<?> unix = Class.forName("com.sun.management.UnixOperatingSystemMXBean");
            openFiles =
                    (Long)
                            unix.getMethod("getOpenFileDescriptorCount")
                                    .invoke(ManagementFactory.getOperatingSystemMXBean());
        } catch (ReflectiveOperationException unavailable) {
            /* reported as unknown */
        }
        String json =
                "{\"blockedCallbacks\":"
                        + live
                        + ",\"poolActive\":"
                        + workers.getActiveCount()
                        + ",\"poolQueued\":"
                        + workers.getQueue().size()
                        + ",\"jvmThreads\":"
                        + ManagementFactory.getThreadMXBean().getThreadCount()
                        + ",\"openFileDescriptors\":"
                        + openFiles
                        + ",\"usedHeapBytes\":"
                        + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                        + ",\"budgetLimit\":"
                        + (budget == null ? -1 : budget.limit())
                        + ",\"budgetInUse\":"
                        + (budget == null ? -1 : budget.inUse())
                        + "}";
        Path directory = Paths.get("target", "admission-evidence");
        Files.createDirectories(directory);
        Files.write(directory.resolve(name + ".json"), json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void finiteWorkerPoolAloneDoesNotBoundDeliveredCallbacks() throws Exception {
        SandboxClient client = builder().build();
        ThreadPoolExecutor workers = pool();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger live = new AtomicInteger();
        List<SandboxExecution> executions = new ArrayList<>();
        try {
            evidence("pool-only-before", live.get(), workers);
            for (int i = 0; i < 3; i++) {
                CountDownLatch entered = new CountDownLatch(1);
                SandboxExecution execution = client.start(blocked(release, live, entered), workers);
                executions.add(execution);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                SandboxCompletion completion = execution.await(Duration.ofSeconds(5));
                assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().streams());
                assertFalse(completion.completeSuccessful());
            }
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(3, live.get());
            evidence("pool-only-delivered", live.get(), workers);
        } finally {
            release.countDown();
            workers.shutdownNow();
            awaitStreams(executions);
            assertEquals(0, live.get());
            evidence("pool-only-released", live.get(), workers);
        }
    }

    @Test
    void repeatedBatchesCannotExceedSharedBudgetAndRecoverAfterCallbacksRelease() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(2);
        SandboxClient first = builder().executionBudget(budget).build();
        SandboxClient second = builder().executionBudget(budget).build();
        ThreadPoolExecutor workers = pool();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger live = new AtomicInteger();
        List<SandboxExecution> executions = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                CountDownLatch entered = new CountDownLatch(1);
                SandboxExecution execution = first.start(blocked(release, live, entered), workers);
                executions.add(execution);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(execution.await(Duration.ofSeconds(5)).completeSuccessful());
            }
            assertEquals(2, budget.inUse());
            assertEquals(2, live.get());
            for (int batch = 0; batch < 20; batch++) {
                SandboxException denied =
                        assertThrows(
                                SandboxException.class,
                                () ->
                                        second.start(
                                                blocked(release, live, new CountDownLatch(1)),
                                                workers));
                assertEquals(SandboxErrorCode.EXECUTION_CAPACITY_EXCEEDED, denied.code());
                assertEquals(SandboxPhase.ADMISSION, denied.phase());
            }
            assertEquals(2, live.get());
            evidence("budget-saturated", live.get(), workers, budget);
            release.countDown();
            awaitStreams(executions);
            assertEquals(0, budget.inUse());
            assertEquals(0, live.get());
            SandboxResult recovered =
                    second.execute(
                            SandboxRequest.builder(workspace, "/bin/sh")
                                    .readPolicy(ReadPolicy.HOST)
                                    .network(NetworkPolicy.ALLOW)
                                    .build());
            assertTrue(recovered.completeSuccessful());
            assertEquals(0, budget.inUse());
            evidence("budget-recovered", live.get(), workers, budget);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            awaitStreams(executions);
        }
    }
}
