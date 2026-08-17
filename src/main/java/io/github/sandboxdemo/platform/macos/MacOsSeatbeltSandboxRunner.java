package io.github.sandboxdemo.platform.macos;

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

/** macOS strategy backed by Apple Seatbelt through /usr/bin/sandbox-exec. */
public final class MacOsSeatbeltSandboxRunner implements SandboxRunner {

    private static final Path SANDBOX_EXEC = java.nio.file.Paths.get("/usr/bin/sandbox-exec");

    @Override
    public String backendName() {
        return "macos-seatbelt";
    }

    /** Executes a benign Seatbelt probe without running caller-controlled code. */
    public static String probeBackend() throws SandboxException, InterruptedException {
        if (!Files.isExecutable(SANDBOX_EXEC)) {
            throw new SandboxBackendUnavailableException(
                    "macOS Seatbelt launcher is unavailable: " + SANDBOX_EXEC);
        }
        List<String> command =
                io.github.sandboxdemo.core.Java8.listOf(
                        SANDBOX_EXEC.toString(),
                        "-p",
                        "(version 1)\n(allow default)\n",
                        "--",
                        "/usr/bin/true");
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().clear();
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxBackendUnavailableException(
                    "failed to start macOS Seatbelt readiness probe: " + e.getMessage());
        }
        boolean completed = process.waitFor(5, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new SandboxBackendUnavailableException(
                    "macOS Seatbelt readiness probe did not complete");
        }
        String output;
        try {
            output =
                    new String(
                            io.github.sandboxdemo.core.Java8.readAllBytes(process.getInputStream()),
                            StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException("failed to read macOS Seatbelt readiness output", e);
        }
        if (process.exitValue() != 0) {
            throw new SandboxBackendUnavailableException(
                    "macOS Seatbelt readiness probe failed (exit="
                            + process.exitValue()
                            + "): "
                            + output.trim());
        }
        return "Seatbelt readiness probe passed: " + SANDBOX_EXEC;
    }

    @Override
    public SandboxResult execute(SandboxRequest request)
            throws SandboxException, InterruptedException {

        if (!Files.isExecutable(SANDBOX_EXEC)) {
            throw new SandboxBackendUnavailableException(
                    "macOS Seatbelt launcher is unavailable: " + SANDBOX_EXEC);
        }

        ValidatedPolicy policy = PathPolicyValidator.validate(request.policy());
        try {
            Map<String, String> environment = EnvironmentPolicy.build(request, policy);
            Path executable =
                    ExecutableResolver.resolve(
                            request.command().executable(),
                            policy.workingDirectory(),
                            environment,
                            policy.allowPathSearch());
            MacOsSeatbeltProfile.GeneratedProfile generated = MacOsSeatbeltProfile.generate(policy);

            probeGeneratedProfile(generated, policy, environment);

            List<String> command = new ArrayList<>();
            command.add(SANDBOX_EXEC.toString());
            command.add("-p");
            command.add(generated.profile());
            command.addAll(generated.definitions());
            command.add("--");
            command.add(executable.toString());
            command.addAll(request.command().arguments());
            SandboxResult result =
                    ProcessExecutor.execute(
                            command,
                            policy,
                            environment,
                            request.standardInput(),
                            request.stdoutConsumer(),
                            request.stderrConsumer(),
                            request.stdoutTextConsumer(),
                            request.stderrTextConsumer(),
                            request.stdoutCharset(),
                            request.stderrCharset(),
                            request.stdoutCharsetAuto(),
                            request.stderrCharsetAuto());
            if (result.exitCode() == 71
                    && result.stderrUtf8().contains("sandbox_apply: Operation not permitted")) {
                throw new SandboxBackendUnavailableException(
                        "macOS refused to enter a nested Seatbelt sandbox. Run this process outside "
                                + "an existing application sandbox.");
            }
            return result;
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    /**
     * Distinguishes a host that cannot enter Seatbelt from a user command that legitimately exits
     * with 134. Some hosted macOS runners abort sandbox-exec instead of returning the older
     * sandbox_apply exit code 71. The probe runs only trusted /bin/sh code in the exact generated
     * profile and private per-request temp directory.
     */
    private static void probeGeneratedProfile(
            MacOsSeatbeltProfile.GeneratedProfile generated,
            ValidatedPolicy policy,
            Map<String, String> environment)
            throws SandboxException, InterruptedException {
        Path marker = policy.privateTempDirectory().resolve("seatbelt-probe");
        List<String> probe = new ArrayList<>();
        probe.add(SANDBOX_EXEC.toString());
        probe.add("-p");
        probe.add(generated.profile());
        probe.addAll(generated.definitions());
        probe.add("--");
        probe.add("/bin/sh");
        probe.add("-c");
        probe.add("printf probe > \"$1\" && rm -f \"$1\"");
        probe.add("seatbelt-probe");
        probe.add(marker.toString());

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(probe).redirectErrorStream(true);
            builder.directory(policy.workingDirectory().toFile());
            builder.environment().clear();
            builder.environment().putAll(environment);
            process = builder.start();
        } catch (IOException e) {
            throw new SandboxBackendUnavailableException(
                    "failed to start the macOS Seatbelt readiness probe: " + e.getMessage());
        }

        boolean completed = process.waitFor(5, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            throw new SandboxBackendUnavailableException(
                    "macOS Seatbelt readiness probe did not complete");
        }

        String output;
        try {
            output =
                    new String(
                            io.github.sandboxdemo.core.Java8.readAllBytes(process.getInputStream()),
                            StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SandboxException("failed to read the macOS Seatbelt readiness probe", e);
        }
        int exitCode = process.exitValue();
        if (exitCode == 71 || exitCode == 134) {
            throw new SandboxBackendUnavailableException(
                    "macOS refused the generated Seatbelt profile (exit="
                            + exitCode
                            + "): "
                            + output.trim());
        }
        if (exitCode != 0) {
            throw new SandboxException(
                    "macOS Seatbelt profile probe failed (exit="
                            + exitCode
                            + "): "
                            + output.trim());
        }
    }
}
