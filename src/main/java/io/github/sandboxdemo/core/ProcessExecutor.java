package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxResult;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

        long started = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(policy.workingDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxException("failed to start sandbox backend: " + command.get(0), e);
        }

        ExecutorService readers =
                Executors.newFixedThreadPool(
                        standardInput.length == 0 ? 2 : 3,
                        runnable -> {
                            Thread thread = new Thread(runnable, "sandbox-output-reader");
                            thread.setDaemon(true);
                            return thread;
                        });
        LimitedOutput stdout = new LimitedOutput(policy.maxOutputBytes());
        LimitedOutput stderr = new LimitedOutput(policy.maxOutputBytes());
        Future<?> stdoutTask =
                readers.submit(
                        () -> {
                            stdout.readFrom(process.getInputStream());
                            return null;
                        });
        Future<?> stderrTask =
                readers.submit(
                        () -> {
                            stderr.readFrom(process.getErrorStream());
                            return null;
                        });
        Future<?> inputTask = null;
        if (standardInput.length == 0) {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // The process can exit before the parent closes an empty stdin.
            }
        } else {
            inputTask =
                    readers.submit(
                            () -> {
                                try (java.io.OutputStream input = process.getOutputStream()) {
                                    input.write(standardInput);
                                    input.flush();
                                } catch (IOException ignored) {
                                    // Closing stdin early is a valid child-process behavior.
                                }
                                return null;
                            });
        }

        Set<Object> observedDescendants = ConcurrentHashMap.newKeySet();
        boolean timedOut = false;
        try {
            timedOut = !waitAndTrack(process, policy.timeout(), observedDescendants);
            terminateProcessTree(process, observedDescendants);
            process.waitFor(5, TimeUnit.SECONDS);
            awaitReader(stdoutTask, process);
            awaitReader(stderrTask, process);

            int exitCode = process.isAlive() ? 124 : process.exitValue();
            return new SandboxResult(
                    exitCode,
                    timedOut,
                    stdout.bytes(),
                    stderr.bytes(),
                    stdout.truncated(),
                    stderr.truncated(),
                    Duration.ofNanos(System.nanoTime() - started));
        } catch (InterruptedException e) {
            terminateProcessTree(process, observedDescendants);
            throw e;
        } finally {
            if (inputTask != null) {
                inputTask.cancel(true);
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // Process termination normally closes the pipe first.
            }
            readers.shutdownNow();
        }
    }

    private static boolean waitAndTrack(
            Process process, Duration timeout, Set<Object> observedDescendants)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            if (process.isAlive()) {
                observedDescendants.addAll(safeDescendants(processHandle(process)));
            }
            for (Object descendant :
                    io.github.sandboxdemo.core.Java8.copyList(observedDescendants)) {
                if (isHandleAlive(descendant)) {
                    observedDescendants.addAll(safeDescendants(descendant));
                }
            }
            boolean treeAlive =
                    process.isAlive()
                            || observedDescendants.stream()
                                    .anyMatch(ProcessExecutor::isHandleAlive);
            if (!treeAlive) {
                return true;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            long waitMillis =
                    Math.max(1, Math.min(50, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Thread.sleep(waitMillis);
        }
    }

    private static void awaitReader(Future<?> task, Process process)
            throws SandboxException, InterruptedException {
        try {
            task.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new SandboxException("failed while capturing sandbox output", e.getCause());
        } catch (TimeoutException e) {
            try {
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (IOException ignored) {
                // The timeout error below is the actionable failure.
            }
            task.cancel(true);
            throw new SandboxException(
                    "sandbox output pipe remained open after its process tree was terminated", e);
        }
    }

    private static void terminateProcessTree(Process process, Set<Object> observedDescendants) {
        observedDescendants.addAll(safeDescendants(processHandle(process)));
        List<Object> descendants = new ArrayList<>(observedDescendants);
        Collections.reverse(descendants);
        descendants.stream()
                .filter(ProcessExecutor::isHandleAlive)
                .forEach(ProcessExecutor::destroy);
        if (process.isAlive()) {
            process.destroy();
        }
        try {
            if (process.isAlive()) {
                process.waitFor(250, TimeUnit.MILLISECONDS);
            } else {
                Thread.sleep(250);
            }
            descendants.stream()
                    .filter(ProcessExecutor::isHandleAlive)
                    .forEach(ProcessExecutor::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            descendants.stream()
                    .filter(ProcessExecutor::isHandleAlive)
                    .forEach(ProcessExecutor::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
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
