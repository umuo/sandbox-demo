package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxExecutionLifecycleTest {
    @TempDir Path workspace;

    private ThreadPoolExecutor pool(int queueSize) {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueSize),
                runnable -> {
                    Thread thread = new Thread(runnable, "lifecycle-test-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private SandboxClient client(SandboxRunner runner) {
        SandboxCapabilities caps =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        runner.backendName(),
                        Collections.singleton(ReadPolicy.DECLARED_ONLY),
                        Collections.singleton(NetworkPolicy.DENY),
                        false);
        return SandboxClient.builder()
                .cleanupGrace(Duration.ofMillis(100))
                .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, "fake"))
                .build();
    }

    private SandboxRequest request(Duration timeout) {
        return SandboxRequest.builder(workspace, "/bin/true")
                .readPolicy(ReadPolicy.DECLARED_ONLY)
                .network(NetworkPolicy.DENY)
                .timeout(timeout)
                .build();
    }

    private SandboxResult success() {
        return new SandboxResult(0, false, new byte[0], new byte[0], false, false, Duration.ZERO);
    }

    @Test
    void queuedCancellationRemovesTaskAndNeverStartsBackend() throws Exception {
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
        ThreadPoolExecutor pool = pool(1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        pool.execute(
                () -> {
                    entered.countDown();
                    try {
                        blocker.await();
                    } catch (InterruptedException ignored) {
                    }
                });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            SandboxExecution execution = client(runner).start(request(Duration.ofSeconds(2)), pool);
            assertTrue(execution.cancel());
            SandboxCompletion cancelled = execution.await(Duration.ofSeconds(1));
            assertEquals(SandboxTerminationReason.CANCELLED, cancelled.reason());
            assertEquals(SandboxPhase.ADMISSION, cancelled.error().get().phase());
            assertTrue(execution.cleanup().confirmed());
            assertEquals(0, pool.getQueue().size());
            blocker.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(0, launches.get());
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void deadlineIncludesQueueAndRejectionHasNoExecutionSideEffects() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "queue-timeout";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        launches.incrementAndGet();
                        return success();
                    }
                };
        ThreadPoolExecutor pool = pool(1);
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        pool.execute(
                () -> {
                    entered.countDown();
                    try {
                        blocker.await();
                    } catch (InterruptedException ignored) {
                    }
                });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            SandboxClient client = client(runner);
            SandboxExecution queued = client.start(request(Duration.ofMillis(80)), pool);
            SandboxCompletion rejected =
                    client.start(request(Duration.ofSeconds(2)), pool).await(Duration.ofSeconds(1));
            assertEquals(SandboxTerminationReason.FAILED, rejected.reason());
            assertEquals(SandboxErrorCode.START_FAILED, rejected.error().get().code());
            assertTrue(rejected.cleanup().confirmed());
            SandboxCompletion timedOut = queued.await(Duration.ofSeconds(1));
            assertEquals(SandboxTerminationReason.TIMEOUT, timedOut.reason());
            assertTrue(queued.cleanup().confirmed());
            assertEquals(0, launches.get());
        } finally {
            blocker.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void uncooperativeBackendHasBoundedCompletionAndIndependentWorkerState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "uncooperative";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        entered.countDown();
                        boolean done = false;
                        while (!done) {
                            try {
                                release.await();
                                done = true;
                            } catch (InterruptedException ignored) {
                            }
                        }
                        return success();
                    }
                };
        ThreadPoolExecutor pool = pool(1);
        try {
            SandboxExecution execution = client(runner).start(request(Duration.ofSeconds(5)), pool);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(execution.cancel());
            SandboxCompletion completion = execution.await(Duration.ofSeconds(1));
            assertEquals(SandboxTerminationReason.CANCELLED, completion.reason());
            assertFalse(completion.cleanup().workerFinished());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().processes());
            assertFalse(completion.completeSuccessful());
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
            assertTrue(execution.cleanup().workerFinished());
            assertFalse(execution.cleanup().confirmed());
            assertSame(completion, execution.completion().get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cancellationWhilePreparationIsBlockedCannotLaunchTargetAfterLateRelease()
            throws Exception {
        CountDownLatch prepared = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger launches = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "blocked-preparation";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("legacy path used");
                    }

                    public SandboxResult execute(
                            SandboxRequest request, SandboxExecutionControl control)
                            throws SandboxException {
                        prepared.countDown();
                        boolean done = false;
                        while (!done) {
                            try {
                                release.await();
                                done = true;
                            } catch (InterruptedException ignored) {
                            }
                        }
                        control.checkActive();
                        launches.incrementAndGet();
                        return success();
                    }
                };
        ThreadPoolExecutor pool = pool(1);
        try {
            SandboxExecution execution = client(runner).start(request(Duration.ofSeconds(5)), pool);
            assertTrue(prepared.await(1, TimeUnit.SECONDS));
            assertTrue(execution.cancel());
            assertEquals(
                    SandboxTerminationReason.CANCELLED,
                    execution.await(Duration.ofSeconds(1)).reason());
            assertFalse(execution.cleanup().workerFinished());
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(0, launches.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void explicitBackendEvidenceCanUpdateIndependentlyWithoutCompletingTheSdkWorker()
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<SandboxExecutionControl> controlReference =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "external-worker";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("legacy path used");
                    }

                    public SandboxResult execute(
                            SandboxRequest request, SandboxExecutionControl control) {
                        controlReference.set(control);
                        control.reportCleanup(
                                new SandboxCleanupResult(
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        true));
                        reported.countDown();
                        boolean done = false;
                        while (!done) {
                            try {
                                release.await();
                                done = true;
                            } catch (InterruptedException ignored) {
                            }
                        }
                        control.reportCleanup(
                                new SandboxCleanupResult(
                                        SandboxCleanupState.UNKNOWN,
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        SandboxCleanupState.CONFIRMED,
                                        true));
                        return success();
                    }
                };
        ThreadPoolExecutor pool = pool(1);
        try {
            SandboxExecution execution = client(runner).start(request(Duration.ofSeconds(2)), pool);
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertFalse(execution.cleanup().workerFinished());
            assertFalse(execution.cleanup().confirmed());
            release.countDown();
            SandboxCompletion completion = execution.await(Duration.ofSeconds(1));
            assertFalse(completion.completeSuccessful());
            assertFalse(execution.cleanup().confirmed());
            controlReference
                    .get()
                    .reportCleanup(
                            new SandboxCleanupResult(
                                    SandboxCleanupState.CONFIRMED,
                                    SandboxCleanupState.CONFIRMED,
                                    SandboxCleanupState.CONFIRMED,
                                    SandboxCleanupState.CONFIRMED,
                                    false));
            assertTrue(execution.cleanup().confirmed());
            assertFalse(completion.cleanup().confirmed());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void legacySuccessCannotClaimResourceConfirmationOrCompleteSuccess() throws Exception {
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "legacy";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        return success();
                    }
                };
        ThreadPoolExecutor pool = pool(1);
        try {
            SandboxExecution execution = client(runner).start(request(Duration.ofSeconds(1)), pool);
            SandboxCompletion result = execution.await(Duration.ofSeconds(1));
            assertTrue(result.result().get().successful());
            assertFalse(result.completeSuccessful());
            assertTrue(execution.cleanup().workerFinished());
            assertEquals(SandboxCleanupState.UNKNOWN, execution.cleanup().processes());
        } finally {
            pool.shutdownNow();
        }
    }
}
