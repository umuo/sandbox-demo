package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxErrorCode;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPhase;
import io.github.sandboxdemo.api.SandboxResult;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared subprocess supervision for the Linux and macOS wrapper backends. */
public final class ProcessExecutor {

    private ProcessExecutor() {}

    public static SandboxResult execute(
            List<String> command,
            ValidatedPolicy policy,
            Map<String, String> environment,
            byte[] standardInput)
            throws SandboxException, InterruptedException {
        return execute(command, policy, environment, standardInput, null, null, null, null);
    }

    public static SandboxResult execute(
            List<String> command,
            ValidatedPolicy policy,
            Map<String, String> environment,
            byte[] standardInput,
            java.util.function.Consumer<byte[]> stdoutConsumer,
            java.util.function.Consumer<byte[]> stderrConsumer)
            throws SandboxException, InterruptedException {
        return execute(
                command,
                policy,
                environment,
                standardInput,
                stdoutConsumer,
                stderrConsumer,
                null,
                null,
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_8,
                false,
                false);
    }

    public static SandboxResult execute(
            List<String> command,
            ValidatedPolicy policy,
            Map<String, String> environment,
            byte[] standardInput,
            java.util.function.Consumer<byte[]> stdoutConsumer,
            java.util.function.Consumer<byte[]> stderrConsumer,
            java.util.function.Consumer<String> stdoutTextConsumer,
            java.util.function.Consumer<String> stderrTextConsumer)
            throws SandboxException, InterruptedException {
        return execute(
                command,
                policy,
                environment,
                standardInput,
                stdoutConsumer,
                stderrConsumer,
                stdoutTextConsumer,
                stderrTextConsumer,
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_8,
                false,
                false);
    }

    public static SandboxResult execute(
            List<String> command,
            ValidatedPolicy policy,
            Map<String, String> environment,
            byte[] standardInput,
            java.util.function.Consumer<byte[]> stdoutConsumer,
            java.util.function.Consumer<byte[]> stderrConsumer,
            java.util.function.Consumer<String> stdoutTextConsumer,
            java.util.function.Consumer<String> stderrTextConsumer,
            Charset stdoutCharset,
            Charset stderrCharset,
            boolean stdoutCharsetAuto,
            boolean stderrCharsetAuto)
            throws SandboxException, InterruptedException {

        return execute(
                command,
                policy,
                environment,
                standardInput,
                stdoutConsumer,
                stderrConsumer,
                stdoutTextConsumer,
                stderrTextConsumer,
                stdoutCharset,
                stderrCharset,
                stdoutCharsetAuto,
                stderrCharsetAuto,
                false);
    }

    public static SandboxResult execute(
            List<String> command,
            ValidatedPolicy policy,
            Map<String, String> environment,
            byte[] standardInput,
            java.util.function.Consumer<byte[]> stdoutConsumer,
            java.util.function.Consumer<byte[]> stderrConsumer,
            java.util.function.Consumer<String> stdoutTextConsumer,
            java.util.function.Consumer<String> stderrTextConsumer,
            Charset stdoutCharset,
            Charset stderrCharset,
            boolean stdoutCharsetAuto,
            boolean stderrCharsetAuto,
            boolean kernelContained)
            throws SandboxException, InterruptedException {
        long started = System.nanoTime();
        ExecutionContext context = ExecutionContext.current();
        boolean owned = context == null;
        if (owned) {
            context = new ExecutionContext(policy.timeout(), Duration.ofSeconds(2));
            context.beginWorker();
            context.managedBackend();
        }
        final ExecutionContext execution = context;
        context.checkActive();
        ExecutionContext.Lease processLease = context.track(ExecutionContext.Resource.PROCESS);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(policy.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IOException failure) {
            processLease.complete(io.github.sandboxdemo.api.SandboxCleanupState.NOT_REQUIRED);
            if (owned) {
                context.finishWorker();
            }
            throw new SandboxException(
                    SandboxErrorCode.START_FAILED,
                    SandboxPhase.START,
                    "failed to start sandbox backend",
                    failure);
        }
        ExecutorService readers =
                Executors.newFixedThreadPool(
                        3,
                        runnable -> {
                            Thread thread = new Thread(runnable, "sandbox-pipe-worker");
                            thread.setDaemon(true);
                            return thread;
                        });
        LimitedOutput stdout =
                new LimitedOutput(
                        policy.maxOutputBytes(),
                        stdoutConsumer,
                        stdoutTextConsumer,
                        stdoutCharset,
                        stdoutCharsetAuto);
        LimitedOutput stderr =
                new LimitedOutput(
                        policy.maxOutputBytes(),
                        stderrConsumer,
                        stderrTextConsumer,
                        stderrCharset,
                        stderrCharsetAuto);
        Future<?> outTask =
                submitTracked(
                        readers,
                        execution,
                        () -> {
                            try (java.io.InputStream input = process.getInputStream()) {
                                stdout.readFrom(input);
                            }
                            return io.github.sandboxdemo.api.SandboxCleanupState.CONFIRMED;
                        });
        Future<?> errTask =
                submitTracked(
                        readers,
                        execution,
                        () -> {
                            try (java.io.InputStream input = process.getErrorStream()) {
                                stderr.readFrom(input);
                            }
                            return io.github.sandboxdemo.api.SandboxCleanupState.CONFIRMED;
                        });
        Future<?> inTask =
                submitTracked(
                        readers,
                        execution,
                        () -> {
                            java.io.OutputStream input = process.getOutputStream();
                            boolean closed = true;
                            try {
                                try {
                                    input.write(standardInput);
                                    input.flush();
                                } catch (IOException ignored) {
                                    /* 子进程提前关闭 stdin 是合法行为。 */
                                }
                            } finally {
                                try {
                                    input.close();
                                } catch (IOException uncertainClosure) {
                                    // 缓冲 close 可能在排空未消费输入时失败；保留旧退出语义，但不伪造关闭证据。
                                    closed = false;
                                }
                            }
                            return closed
                                    ? io.github.sandboxdemo.api.SandboxCleanupState.CONFIRMED
                                    : io.github.sandboxdemo.api.SandboxCleanupState.UNKNOWN;
                        });
        Set<Object> descendants = ConcurrentHashMap.newKeySet();
        boolean interrupted = false;
        boolean timedOut = false;
        SandboxException captureError = null;
        try {
            long localDeadline =
                    System.nanoTime()
                            + Math.min(policy.timeout().toNanos(), context.remainingNanos());
            while (true) {
                descendants.addAll(safeDescendants(processHandle(process)));
                for (Object child : new ArrayList<>(descendants)) {
                    descendants.addAll(safeDescendants(child));
                }
                if (context.stopReason() != null) {
                    timedOut =
                            context.stopReason()
                                    == io.github.sandboxdemo.api.SandboxTerminationReason.TIMEOUT;
                    break;
                }
                if (!process.isAlive()
                        && descendants.stream().noneMatch(ProcessExecutor::isHandleAlive)) {
                    break;
                }
                if (localDeadline - System.nanoTime() <= 0) {
                    context.stop(io.github.sandboxdemo.api.SandboxTerminationReason.TIMEOUT);
                    timedOut = true;
                    break;
                }
                Thread.sleep(
                        Math.max(
                                1,
                                Math.min(
                                        20,
                                        TimeUnit.NANOSECONDS.toMillis(
                                                localDeadline - System.nanoTime()))));
            }
        } catch (InterruptedException callerInterrupted) {
            interrupted = true;
            context.stop(io.github.sandboxdemo.api.SandboxTerminationReason.CANCELLED);
        } finally {
            // Never close a pipe or invoke Process.destroy on the supervising thread.
            Thread.interrupted();
            context.beginCleanup();
            ExecutionContext.Lease killerLease = context.track(ExecutionContext.Resource.HOST);
            Thread killer =
                    new Thread(
                            () -> {
                                try {
                                    for (Object child : descendants) {
                                        destroyForcibly(child);
                                    }
                                    Object root = processHandle(process);
                                    if (root != null) {
                                        destroyForcibly(root);
                                    } else if (process.isAlive()) {
                                        process.destroyForcibly();
                                    }
                                } finally {
                                    killerLease.complete(
                                            io.github.sandboxdemo.api.SandboxCleanupState
                                                    .CONFIRMED);
                                }
                            },
                            "sandbox-process-terminator");
            killer.setDaemon(true);
            killer.start();
            while (process.isAlive() && context.cleanupRemainingNanos() > 0) {
                try {
                    process.waitFor(
                            Math.max(
                                    1,
                                    Math.min(
                                            20,
                                            TimeUnit.NANOSECONDS.toMillis(
                                                    context.cleanupRemainingNanos()))),
                            TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            processLease.complete(
                    process.isAlive()
                            ? io.github.sandboxdemo.api.SandboxCleanupState.FAILED
                            : kernelContained
                                    ? io.github.sandboxdemo.api.SandboxCleanupState.CONFIRMED
                                    : io.github.sandboxdemo.api.SandboxCleanupState.UNKNOWN);
            for (Future<?> task : new Future<?>[] {outTask, errTask, inTask}) {
                try {
                    if (context.cleanupRemainingNanos() > 0) {
                        task.get(context.cleanupRemainingNanos(), TimeUnit.NANOSECONDS);
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException e) {
                    captureError =
                            new SandboxException(
                                    SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                                    SandboxPhase.OUTPUT,
                                    "failed capturing sandbox pipes",
                                    e.getCause());
                } catch (TimeoutException e) {
                    /* Actual task finally, not Future cancellation, confirms closure. */
                }
            }
            try {
                if (killer.isAlive() && context.cleanupRemainingNanos() > 0) {
                    killer.join(
                            Math.max(
                                    1,
                                    TimeUnit.NANOSECONDS.toMillis(
                                            context.cleanupRemainingNanos())));
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
            readers.shutdownNow();
            if (owned) {
                context.finishWorker();
            }
            context.endPhaseCleanup();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (interrupted
                && context.stopReason()
                        != io.github.sandboxdemo.api.SandboxTerminationReason.TIMEOUT) {
            throw new InterruptedException("sandbox execution interrupted");
        }
        if (context.stopReason() == io.github.sandboxdemo.api.SandboxTerminationReason.TIMEOUT) {
            timedOut = true;
        }
        if (captureError != null && context.stopReason() == null) {
            throw captureError;
        }
        int exitCode = process.isAlive() ? 124 : process.exitValue();
        return new SandboxResult(
                exitCode,
                timedOut,
                stdout.bytes(),
                stderr.bytes(),
                stdout.truncated(),
                stderr.truncated(),
                Duration.ofNanos(System.nanoTime() - started));
    }

    private static Future<?> submitTracked(
            ExecutorService executor,
            ExecutionContext context,
            java.util.concurrent.Callable<io.github.sandboxdemo.api.SandboxCleanupState> task) {
        ExecutionContext.Lease lease = context.track(ExecutionContext.Resource.STREAM);
        return executor.submit(
                () -> {
                    io.github.sandboxdemo.api.SandboxCleanupState state =
                            io.github.sandboxdemo.api.SandboxCleanupState.UNKNOWN;
                    try {
                        state = java.util.Objects.requireNonNull(task.call(), "resource state");
                        return null;
                    } finally {
                        lease.complete(state);
                    }
                });
    }

    private static Object processHandle(Process process) {
        try {
            return Process.class.getMethod("toHandle").invoke(process);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }

    private static List<Object> safeDescendants(Object process) {
        if (process == null) {
            return io.github.sandboxdemo.core.Java8.listOf();
        }
        try {
            Class<?> processHandle = Class.forName("java.lang.ProcessHandle");
            Object descendants = processHandle.getMethod("descendants").invoke(process);
            try (java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) descendants) {
                return stream.map(value -> (Object) value)
                        .collect(java.util.stream.Collectors.toList());
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // Some outer sandboxes deny process-table inspection. Platform-native
            // PID namespaces/Job Objects remain the primary process-tree boundary.
            return io.github.sandboxdemo.core.Java8.listOf();
        }
    }

    private static boolean isHandleAlive(Object process) {
        try {
            Class<?> processHandle = Class.forName("java.lang.ProcessHandle");
            return (Boolean) processHandle.getMethod("isAlive").invoke(process);
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return false;
        }
    }

    private static void destroy(Object process) {
        try {
            Class<?> processHandle = Class.forName("java.lang.ProcessHandle");
            processHandle.getMethod("destroy").invoke(process);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A later force-kill or the native sandbox lifecycle is the fallback.
        }
    }

    private static void destroyForcibly(Object process) {
        try {
            Class<?> processHandle = Class.forName("java.lang.ProcessHandle");
            processHandle.getMethod("destroyForcibly").invoke(process);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // The native sandbox launcher may already have reaped the process.
        }
    }
}
