package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.ExecutionContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxExecutionBudgetTest {
    @TempDir Path workspace;

    private SandboxResult success() {
        return new SandboxResult(0, false, new byte[0], new byte[0], false, false, Duration.ZERO);
    }

    private SandboxRequest request() {
        return SandboxRequest.builder(workspace, "/synthetic/trusted")
                .readPolicy(ReadPolicy.HOST)
                .network(NetworkPolicy.ALLOW)
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    private SandboxClient client(SandboxRunner runner, SandboxExecutionBudget budget) {
        SandboxCapabilities caps =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        runner.backendName(),
                        Collections.singleton(ReadPolicy.HOST),
                        Collections.singleton(NetworkPolicy.ALLOW),
                        false);
        return SandboxClient.builder()
                .executionBudget(budget)
                .cleanupGrace(Duration.ofMillis(100))
                .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, "synthetic"))
                .build();
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

    private void capacityError(SandboxException error) {
        assertEquals(SandboxErrorCode.EXECUTION_CAPACITY_EXCEEDED, error.code());
        assertEquals(SandboxPhase.ADMISSION, error.phase());
    }

    private void waitForZero(SandboxExecutionBudget budget) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (budget.inUse() != 0 && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertEquals(0, budget.inUse());
    }

    @Test
    void defaultClientsShareFiniteBudgetAndExplicitBudgetsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> SandboxExecutionBudget.bounded(0));
        assertThrows(IllegalArgumentException.class, () -> SandboxExecutionBudget.bounded(-1));
        assertThrows(
                NullPointerException.class, () -> SandboxClient.builder().executionBudget(null));
        assertSame(
                SandboxClient.create().executionBudget(), SandboxClient.create().executionBudget());
        assertEquals(32, SandboxClient.create().executionBudget().limit());
    }

    @Test
    void sharedBudgetRejectsAllEntryPointsUntilCancelledWorkerActuallyStops() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger admissions = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "blocked";
                    }

                    public boolean supportsResourceLimits(SandboxResourceLimits limits) {
                        admissions.incrementAndGet();
                        return true;
                    }

                    public SandboxResult execute(SandboxRequest ignored)
                            throws InterruptedException {
                        entered.countDown();
                        release.await();
                        return success();
                    }
                };
        SandboxClient first = client(runner, budget), other = client(runner, budget);
        ThreadPoolExecutor workers = pool();
        try {
            SandboxExecution execution = first.start(request(), workers);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            int previous = admissions.get();
            for (int i = 0; i < 20; i++) {
                capacityError(
                        assertThrows(
                                SandboxException.class, () -> other.start(request(), workers)));
            }
            capacityError(assertThrows(SandboxException.class, () -> other.execute(request())));
            SandboxPolicyReadiness check = other.readiness(request());
            assertFalse(check.ready());
            capacityError(check.error().get());
            assertEquals(SandboxCleanupState.NOT_REQUIRED, check.cleanup().processes());
            capacityError(assertThrows(SandboxException.class, () -> other.validate(request())));
            assertEquals(
                    previous, admissions.get(), "exhausted requests must not call the backend");
            assertTrue(execution.cancel());
            assertEquals(
                    SandboxTerminationReason.CANCELLED,
                    execution.await(Duration.ofSeconds(2)).reason());
            assertEquals(1, budget.inUse(), "delivery must not release the blocked worker");
        } finally {
            release.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        waitForZero(budget);
        assertTrue(other.execute(request()).successful());
        assertEquals(0, budget.inUse());
    }

    @Test
    void queuedCancellationExpiryAndPoolRejectionReturnReservations() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        AtomicInteger launches = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "queued";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        launches.incrementAndGet();
                        return success();
                    }
                };
        SandboxClient client = client(runner, budget);
        ThreadPoolExecutor workers = pool();
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        workers.execute(
                () -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                    }
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            SandboxExecution queued = client.start(request(), workers);
            assertEquals(1, budget.inUse());
            assertTrue(queued.cancel());
            assertEquals(
                    SandboxTerminationReason.CANCELLED,
                    queued.await(Duration.ofSeconds(2)).reason());
            assertEquals(0, budget.inUse());
            SandboxExecution expiry =
                    client.start(
                            SandboxRequest.builder(workspace, "/synthetic/trusted")
                                    .readPolicy(ReadPolicy.HOST)
                                    .network(NetworkPolicy.ALLOW)
                                    .timeout(Duration.ofMillis(80))
                                    .build(),
                            workers);
            assertEquals(
                    SandboxTerminationReason.TIMEOUT, expiry.await(Duration.ofSeconds(2)).reason());
            assertEquals(0, budget.inUse());
        } finally {
            release.countDown();
            workers.shutdown();
        }
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, launches.get());
        SandboxCompletion rejected = client.start(request(), workers).await(Duration.ofSeconds(2));
        assertEquals(SandboxErrorCode.START_FAILED, rejected.error().get().code());
        assertEquals(0, budget.inUse());
    }

    @Test
    void validationAndBackendFailuresDoNotLeakAdmissionSlots() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        SandboxRunner unsupported =
                new SandboxRunner() {
                    public String backendName() {
                        return "unsupported";
                    }

                    public boolean supportsResourceLimits(SandboxResourceLimits limits) {
                        throw new IllegalStateException();
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError();
                    }
                };
        SandboxClient denied = client(unsupported, budget);
        ThreadPoolExecutor workers = pool();
        try {
            assertThrows(SandboxException.class, () -> denied.start(request(), workers));
            assertEquals(0, budget.inUse());
            assertThrows(SandboxException.class, () -> denied.execute(request()));
            assertEquals(0, budget.inUse());
            assertFalse(denied.readiness(request()).ready());
            assertEquals(0, budget.inUse());
            SandboxRunner failed =
                    new SandboxRunner() {
                        public String backendName() {
                            return "failed";
                        }

                        public SandboxResult execute(SandboxRequest request)
                                throws SandboxException {
                            throw new SandboxException(
                                    SandboxErrorCode.START_FAILED, SandboxPhase.START, "synthetic");
                        }
                    };
            SandboxClient failing = client(failed, budget);
            assertThrows(SandboxException.class, () -> failing.execute(request()));
            assertEquals(0, budget.inUse());
            assertEquals(
                    SandboxTerminationReason.FAILED,
                    failing.start(request(), workers).await(Duration.ofSeconds(2)).reason());
            assertEquals(0, budget.inUse());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void reportedCleanupCannotReleaseActiveLeaseAndUnknownDoesNotMeanLiveWork() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        AtomicReference<ExecutionContext.Lease> stream = new AtomicReference<>();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "tracked";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        ExecutionContext context = ExecutionContext.current();
                        context.managedBackend();
                        stream.set(context.track(ExecutionContext.Resource.STREAM));
                        context.reportCleanup(
                                new SandboxCleanupResult(
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        true));
                        return success();
                    }
                };
        ThreadPoolExecutor workers = pool();
        SandboxClient client = client(runner, budget);
        try {
            SandboxExecution execution = client.start(request(), workers);
            execution.await(Duration.ofSeconds(2));
            assertTrue(execution.cleanup().workerFinished());
            assertEquals(SandboxCleanupState.UNKNOWN, execution.cleanup().streams());
            assertEquals(1, budget.inUse());
            stream.get().complete(SandboxCleanupState.UNKNOWN);
            stream.get().complete(SandboxCleanupState.CONFIRMED);
            assertEquals(0, budget.inUse());
            assertEquals(SandboxCleanupState.UNKNOWN, execution.cleanup().streams());
            assertFalse(execution.cleanup().confirmed());
            assertTrue(client.execute(request()).successful());
            assertEquals(
                    1, budget.inUse(), "synchronous delivery must retain its active lease too");
            stream.get().complete(SandboxCleanupState.UNKNOWN);
            assertEquals(0, budget.inUse());
        } finally {
            if (stream.get() != null) {
                stream.get().complete(SandboxCleanupState.UNKNOWN);
            }
            workers.shutdownNow();
        }
    }

    @Test
    void lastLeaseAndWorkerCanFinishConcurrentlyWithoutDoubleRelease() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 30; i++) {
                ExecutionContext context =
                        new ExecutionContext(
                                Duration.ofSeconds(2), Duration.ofMillis(100), budget.reserve());
                context.beginWorker();
                ExecutionContext.Lease stream = context.track(ExecutionContext.Resource.STREAM);
                CountDownLatch begin = new CountDownLatch(1);
                Future<?> worker =
                        tasks.submit(
                                () -> {
                                    begin.await();
                                    context.finishWorker();
                                    return null;
                                });
                Future<?> pipe =
                        tasks.submit(
                                () -> {
                                    begin.await();
                                    stream.complete(SandboxCleanupState.UNKNOWN);
                                    stream.complete(SandboxCleanupState.CONFIRMED);
                                    return null;
                                });
                begin.countDown();
                worker.get(5, TimeUnit.SECONDS);
                pipe.get(5, TimeUnit.SECONDS);
                assertEquals(0, budget.inUse());
                assertThrows(
                        IllegalStateException.class,
                        () -> context.track(ExecutionContext.Resource.STREAM));
            }
        } finally {
            tasks.shutdownNow();
        }
    }
}
