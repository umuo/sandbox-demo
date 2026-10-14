package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** 有界交付句柄。监视器只发布状态，不调用用户代码或等待管道锁。 */
public final class ManagedExecution implements SandboxExecution {
    private static final ScheduledThreadPoolExecutor CLOCK;

    static {
        CLOCK =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "sandbox-deadline-monitor");
                            thread.setDaemon(true);
                            return thread;
                        });
        CLOCK.setRemoveOnCancelPolicy(true);
    }

    private final ExecutionContext context;
    private final AtomicReference<SandboxCompletion> completion = new AtomicReference<>();
    private final CountDownLatch delivered = new CountDownLatch(1);
    private ScheduledFuture<?> expiry;
    private ScheduledFuture<?> delivery;
    private FutureTask<Void> task;
    private ThreadPoolExecutor workers;

    public ManagedExecution(ExecutionContext context) {
        this.context = context;
    }

    public synchronized void submit(Callable<SandboxResult> operation, ThreadPoolExecutor workers) {
        this.workers = workers;
        task =
                new FutureTask<Void>(
                        () -> {
                            run(operation);
                            return null;
                        });
        expiry =
                CLOCK.schedule(
                        () -> {
                            context.expire();
                            cancelQueued();
                        },
                        context.remainingNanos(),
                        TimeUnit.NANOSECONDS);
        delivery =
                CLOCK.schedule(
                        this::deliverStopped,
                        context.deliveryRemainingNanos(),
                        TimeUnit.NANOSECONDS);
        try {
            workers.execute(task);
        } catch (RejectedExecutionException rejected) {
            context.finishWorker();
            publish(
                    new SandboxCompletion(
                            executionId(),
                            SandboxTerminationReason.FAILED,
                            null,
                            new SandboxException(
                                    SandboxErrorCode.START_FAILED,
                                    SandboxPhase.ADMISSION,
                                    "execution pool rejected request",
                                    rejected),
                            context.cleanup()));
        }
    }

    private void run(Callable<SandboxResult> operation) {
        if (!context.beginWorker()) {
            deliverStopped();
            return;
        }
        ExecutionContext previous = ExecutionContext.attach(context);
        SandboxResult result = null;
        SandboxException error = null;
        try {
            context.checkActive();
            result = java.util.Objects.requireNonNull(operation.call(), "backend result");
        } catch (InterruptedException interrupted) {
            context.stop(SandboxTerminationReason.CANCELLED);
            error =
                    new SandboxException(
                            SandboxErrorCode.CANCELLED,
                            SandboxPhase.EXECUTION,
                            "caller cancelled execution",
                            interrupted);
        } catch (SandboxException failure) {
            error = failure;
        } catch (Throwable failure) {
            error =
                    new SandboxException(
                            SandboxErrorCode.UNKNOWN,
                            SandboxPhase.EXECUTION,
                            "backend failed",
                            failure);
        } finally {
            context.finishWorker();
            ExecutionContext.restore(previous);
            Thread.interrupted();
        }
        SandboxTerminationReason reason = context.stopReason();
        if (reason == null) {
            reason =
                    error != null
                            ? SandboxTerminationReason.FAILED
                            : result.timedOut()
                                    ? SandboxTerminationReason.TIMEOUT
                                    : SandboxTerminationReason.EXITED;
        }
        if (reason == SandboxTerminationReason.TIMEOUT
                || reason == SandboxTerminationReason.CANCELLED) {
            SandboxErrorCode code =
                    reason == SandboxTerminationReason.TIMEOUT
                            ? SandboxErrorCode.TIMEOUT
                            : SandboxErrorCode.CANCELLED;
            if (error == null || error.code() != code) {
                error =
                        new SandboxException(
                                code, SandboxPhase.EXECUTION, "execution stopped", error);
            }
        }
        publish(new SandboxCompletion(executionId(), reason, result, error, context.cleanup()));
    }

    private void deliverStopped() {
        SandboxTerminationReason reason = context.stopReason();
        if (reason == null) {
            reason = SandboxTerminationReason.TIMEOUT;
            context.stop(reason);
        }
        publish(
                new SandboxCompletion(
                        executionId(),
                        reason,
                        null,
                        new SandboxException(
                                reason == SandboxTerminationReason.CANCELLED
                                        ? SandboxErrorCode.CANCELLED
                                        : SandboxErrorCode.TIMEOUT,
                                context.started() ? SandboxPhase.CLEANUP : SandboxPhase.ADMISSION,
                                "completion deadline reached; consult independent cleanup snapshot"),
                        context.cleanup()));
    }

    private void publish(SandboxCompletion value) {
        if (completion.compareAndSet(null, value)) {
            delivered.countDown();
            synchronized (this) {
                if (expiry != null) {
                    expiry.cancel(false);
                }
                if (delivery != null) {
                    delivery.cancel(false);
                }
            }
        }
    }

    public String executionId() {
        return context.executionId();
    }

    public synchronized boolean cancel() {
        if (completion.get() != null || !context.cancel()) {
            return false;
        }
        if (delivery != null) {
            delivery.cancel(false);
        }
        delivery =
                CLOCK.schedule(
                        this::deliverStopped,
                        context.cleanupRemainingNanos(),
                        TimeUnit.NANOSECONDS);
        cancelQueued();
        return true;
    }

    private synchronized void cancelQueued() {
        if (context.cancelBeforeStart()) {
            task.cancel(false);
            workers.remove(task);
            deliverStopped();
        }
    }

    public Optional<SandboxCompletion> completion() {
        return Optional.ofNullable(completion.get());
    }

    public SandboxCompletion await() throws InterruptedException {
        delivered.await();
        return completion.get();
    }

    public SandboxCompletion await(Duration maximumWait)
            throws InterruptedException, TimeoutException {
        if (maximumWait.isNegative()) {
            throw new IllegalArgumentException("maximumWait must not be negative");
        }
        if (!delivered.await(maximumWait.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("execution completion is pending");
        }
        return completion.get();
    }

    public SandboxCleanupResult cleanup() {
        return context.cleanup();
    }
}
