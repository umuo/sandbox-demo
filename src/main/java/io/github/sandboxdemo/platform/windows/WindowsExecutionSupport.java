package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Pointer;
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.ExecutionContext;
import java.util.concurrent.*;

/** Windows 原生等待使用短轮询；只有 Job 活跃进程数为零才能确认树回收。 */
final class WindowsExecutionSupport {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(WindowsExecutionSupport.class.getName());

    private static SandboxCleanupState unknown(String operation) {
        LOG.fine(operation + " failed, Win32=" + com.sun.jna.Native.getLastError());
        return SandboxCleanupState.UNKNOWN;
    }

    private WindowsExecutionSupport() {}

    static int waitForProcess(Pointer process, ExecutionContext context) throws SandboxException {
        while (true) {
            if (Thread.currentThread().isInterrupted() || context.cancellationRequested()) {
                context.stop(SandboxTerminationReason.CANCELLED);
                throw new SandboxException(
                        SandboxErrorCode.CANCELLED,
                        SandboxPhase.EXECUTION,
                        "Windows execution cancelled");
            }
            if (context.stopReason() == SandboxTerminationReason.TIMEOUT) {
                return 258;
            }
            int millis =
                    (int)
                            Math.max(
                                    1,
                                    Math.min(
                                            20,
                                            TimeUnit.NANOSECONDS.toMillis(
                                                    context.remainingNanos())));
            int status = Kernel32.INSTANCE.WaitForSingleObject(process, millis);
            if (status == 0) {
                return status;
            }
            if (status != 258) {
                throw new SandboxException(
                        SandboxErrorCode.UNKNOWN,
                        SandboxPhase.EXECUTION,
                        "Windows process wait failed");
            }
        }
    }

    static SandboxCleanupState terminateJob(Pointer job, ExecutionContext context) {
        context.beginCleanup();
        if (!Kernel32.INSTANCE.TerminateJobObject(job, 125)) {
            return SandboxCleanupState.FAILED;
        }
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                WindowsNative.JOBOBJECT_BASIC_ACCOUNTING_INFORMATION information =
                        new WindowsNative.JOBOBJECT_BASIC_ACCOUNTING_INFORMATION();
                if (!Kernel32.INSTANCE.QueryInformationJobObject(
                        job, 1, information.getPointer(), information.size(), null)) {
                    return unknown("QueryInformationJobObject");
                }
                information.read();
                if (information.ActiveProcesses == 0) {
                    return SandboxCleanupState.CONFIRMED;
                }
                if (context.cleanupRemainingNanos() == 0) {
                    return SandboxCleanupState.FAILED;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static SandboxCleanupState terminateRoot(Pointer root, ExecutionContext context) {
        if (root == null || Pointer.nativeValue(root) == -1L) {
            return SandboxCleanupState.NOT_REQUIRED;
        }
        context.beginCleanup();
        if (Kernel32.INSTANCE.WaitForSingleObject(root, 0) == 0) {
            return SandboxCleanupState.CONFIRMED;
        }
        boolean terminationRequested = Kernel32.INSTANCE.TerminateProcess(root, 125);
        if (!terminationRequested) {
            // Job termination can race this call: an exiting process may reject
            // TerminateProcess before its handle becomes signalled. Only the wait
            // proves reclamation; keep observing it within the same cleanup grace.
            unknown("TerminateProcess");
        }
        while (context.cleanupRemainingNanos() > 0) {
            int wait =
                    Kernel32.INSTANCE.WaitForSingleObject(
                            root,
                            (int)
                                    Math.max(
                                            1,
                                            Math.min(
                                                    20,
                                                    TimeUnit.NANOSECONDS.toMillis(
                                                            context.cleanupRemainingNanos()))));
            if (wait == 0) {
                return SandboxCleanupState.CONFIRMED;
            }
            if (wait != 258) {
                return unknown("WaitForSingleObject(root), status=" + wait);
            }
        }
        return terminationRequested ? SandboxCleanupState.FAILED : SandboxCleanupState.UNKNOWN;
    }

    static <T> T await(Future<T> future, ExecutionContext context)
            throws SandboxException, InterruptedException {
        try {
            return future.get(context.cleanupRemainingNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SandboxException) {
                throw (SandboxException) e.getCause();
            }
            throw new SandboxException(
                    SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                    SandboxPhase.OUTPUT,
                    "Windows pipe task failed",
                    e.getCause());
        } catch (TimeoutException e) {
            throw new SandboxException(
                    SandboxErrorCode.CLEANUP_FAILED,
                    SandboxPhase.CLEANUP,
                    "Windows pipe cleanup unconfirmed",
                    e);
        }
    }

    static <T> Future<T> submit(
            ExecutorService workers, ExecutionContext context, Callable<T> work) {
        ExecutionContext.Lease lease = context.track(ExecutionContext.Resource.STREAM);
        return workers.submit(
                () -> {
                    boolean completed = false;
                    try {
                        T result = work.call();
                        completed = true;
                        return result;
                    } finally {
                        lease.complete(
                                completed
                                        ? SandboxCleanupState.CONFIRMED
                                        : SandboxCleanupState.UNKNOWN);
                    }
                });
    }
}
