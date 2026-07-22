package io.github.sandboxdemo.platform.linux;

import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.core.EnvironmentPolicy;
import io.github.sandboxdemo.core.ExecutableResolver;
import io.github.sandboxdemo.core.PathPolicyValidator;
import io.github.sandboxdemo.core.ProcessExecutor;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Linux strategy backed by bubblewrap namespaces plus a seccomp network policy. */
public final class LinuxBubblewrapSandboxRunner implements SandboxRunner {

    private static final List<Path> RUNTIME_ROOTS =
            io.github.sandboxdemo.core.Java8.listOf(
                    java.nio.file.Paths.get("/usr"),
                    java.nio.file.Paths.get("/bin"),
                    java.nio.file.Paths.get("/sbin"),
                    java.nio.file.Paths.get("/lib"),
                    java.nio.file.Paths.get("/lib64"),
                    java.nio.file.Paths.get("/etc/ld.so.cache"),
                    java.nio.file.Paths.get("/etc/ld.so.conf"),
                    java.nio.file.Paths.get("/etc/ld.so.conf.d"),
                    java.nio.file.Paths.get("/etc/nsswitch.conf"),
                    java.nio.file.Paths.get("/etc/passwd"),
                    java.nio.file.Paths.get("/etc/group"),
                    java.nio.file.Paths.get("/etc/hosts"),
                    java.nio.file.Paths.get("/etc/resolv.conf"),
                    java.nio.file.Paths.get("/etc/localtime"),
                    java.nio.file.Paths.get("/etc/ssl/certs"),
                    java.nio.file.Paths.get("/etc/pki"),
                    java.nio.file.Paths.get("/etc/ca-certificates"));

    @Override
    public String backendName() {
        return "linux-bubblewrap";
    }

    /** Executes a benign namespace probe without running caller-controlled code. */
    public static String probeBackend() throws SandboxException, InterruptedException {
        Path bwrap = findBubblewrap();
        List<String> command =
                io.github.sandboxdemo.core.Java8.listOf(
                        bwrap.toString(),
                        "--cap-drop",
                        "ALL",
                        "--die-with-parent",
                        "--new-session",
                        "--unshare-user",
                        "--unshare-pid",
                        "--unshare-ipc",
                        "--unshare-uts",
                        "--ro-bind",
                        "/",
                        "/",
                        "--dev",
                        "/dev",
                        "--proc",
                        "/proc",
                        "--",
                        "/bin/true");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new SandboxBackendUnavailableException(
                    "failed to start bubblewrap readiness probe: " + e.getMessage());
        }
        boolean completed = process.waitFor(5, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new SandboxBackendUnavailableException(
                    "bubblewrap namespace readiness probe did not complete");
        }
        String output;
        try {
            output =
                    new String(
                            io.github.sandboxdemo.core.Java8.readAllBytes(process.getInputStream()),
                            StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException("failed to read bubblewrap readiness output", e);
        }
        if (process.exitValue() != 0) {
            throw new SandboxBackendUnavailableException(
                    "bubblewrap namespace readiness probe failed (exit="
                            + process.exitValue()
                            + "): "
                            + output.trim());
        }
        return "bubblewrap namespace probe passed: " + bwrap;
    }

    @Override
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {

        ValidatedPolicy policy = PathPolicyValidator.validate(request.policy());
        try {
            Map<String, String> environment = EnvironmentPolicy.build(request, policy);
            Path bwrap = findBubblewrap();
            Path executable =
                    ExecutableResolver.resolve(
                            request.command().executable(),
                            policy.workingDirectory(),
                            environment,
                            policy.allowPathSearch());
            Path seccompFilter =
                    LinuxSeccompFilter.write(policy.privateTempDirectory(), policy.networkPolicy());

            List<String> sandboxCommand = new ArrayList<>();
            sandboxCommand.add(bwrap.toString());
            sandboxCommand.add("--seccomp");
            sandboxCommand.add("3");
            sandboxCommand.add("--cap-drop");
            sandboxCommand.add("ALL");
            sandboxCommand.add("--die-with-parent");
            sandboxCommand.add("--new-session");
            sandboxCommand.add("--unshare-user");
            sandboxCommand.add("--unshare-pid");
            sandboxCommand.add("--unshare-ipc");
            sandboxCommand.add("--unshare-uts");
            // Network denial is enforced by the seccomp program, which rejects socket,
            // socketpair, and io_uring setup. Avoiding --unshare-net also avoids a known
            // host-kernel/AppArmor failure while bubblewrap configures the new loopback device.

            if (policy.readPolicy() == ReadPolicy.HOST) {
                addPathPair(sandboxCommand, "--ro-bind", java.nio.file.Paths.get("/"));
            } else {
                // Without a root bind bubblewrap starts from an empty tmpfs. Only
                // runtime and caller-declared roots become visible.
                for (Path runtimeRoot : RUNTIME_ROOTS) {
                    if (Files.exists(runtimeRoot)) {
                        addPathPair(sandboxCommand, "--ro-bind", runtimeRoot);
                    }
                }
                // The private temp normally lives below /tmp. Create its parent
                // before adding caller/internal bind mounts beneath it.
                sandboxCommand.add("--dir");
                sandboxCommand.add("/tmp");
                for (Path readableRoot : policy.readableRoots()) {
                    addPathPair(sandboxCommand, "--ro-bind", readableRoot);
                }
            }
            sandboxCommand.add("--dev");
            sandboxCommand.add("/dev");
            sandboxCommand.add("--proc");
            sandboxCommand.add("/proc");

            // Writable mounts override read-only mounts; protected paths override
            // them again. The ordering is part of the security policy.
            for (Path writableRoot : policy.writableRoots()) {
                addPathPair(sandboxCommand, "--bind", writableRoot);
            }
            for (Path protectedPath : policy.protectedPaths()) {
                addPathPair(sandboxCommand, "--ro-bind", protectedPath);
            }

            sandboxCommand.add("--chdir");
            sandboxCommand.add(policy.workingDirectory().toString());
            sandboxCommand.add("--");
            sandboxCommand.add(executable.toString());
            sandboxCommand.addAll(request.command().arguments());

            // Open the BPF program on descriptor 3 and then replace this fixed,
            // trusted shell with bwrap. User command arguments remain distinct argv
            // entries and are never evaluated by the host shell.
            List<String> command = new ArrayList<>();
            command.add("/bin/sh");
            command.add("-c");
            command.add("exec 3<\"$1\"; shift; exec \"$@\"");
            command.add("sandbox-seccomp-loader");
            command.add(seccompFilter.toString());
            command.addAll(sandboxCommand);
            SandboxResult result =
                    ProcessExecutor.execute(command, policy, environment, request.standardInput());
            if (isBubblewrapBootstrapFailure(result)) {
                throw new SandboxBackendUnavailableException(
                        "bubblewrap is installed but the host refused its namespace setup: "
                                + result.stderrUtf8().trim());
            }
            return result;
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    private static boolean isBubblewrapBootstrapFailure(SandboxResult result) {
        if (result.successful()) {
            return false;
        }
        String stderr = result.stderrUtf8();
        return stderr.contains("bwrap: No permissions to create a new namespace")
                || stderr.contains("bwrap: Creating new namespace failed")
                || stderr.contains("bwrap: Failed to make / slave")
                || stderr.contains("bwrap: loopback: Failed RTM_NEWADDR")
                || stderr.contains("bwrap: setting up uid map");
    }

    private static void addPathPair(List<String> command, String option, Path path) {
        command.add(option);
        command.add(path.toString());
        command.add(path.toString());
    }

    private static Path findBubblewrap() throws SandboxBackendUnavailableException {
        String override = System.getenv("SANDBOX_BWRAP");
        List<Path> candidates =
                override == null || io.github.sandboxdemo.core.Java8.isBlank(override)
                        ? io.github.sandboxdemo.core.Java8.listOf(
                                java.nio.file.Paths.get("/usr/bin/bwrap"),
                                java.nio.file.Paths.get("/bin/bwrap"))
                        : io.github.sandboxdemo.core.Java8.listOf(
                                java.nio.file.Paths.get(override));
        for (Path candidate : candidates) {
            if (candidate.isAbsolute() && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new SandboxBackendUnavailableException(
                "bubblewrap is required. Install it with the OS package manager "
                        + "or set SANDBOX_BWRAP to an absolute executable path.");
    }
}
