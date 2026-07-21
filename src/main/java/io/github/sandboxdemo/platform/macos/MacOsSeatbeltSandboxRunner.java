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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** macOS strategy backed by Apple Seatbelt through /usr/bin/sandbox-exec. */
public final class MacOsSeatbeltSandboxRunner implements SandboxRunner {

    private static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");

    @Override
    public String backendName() {
        return "macos-seatbelt";
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

            List<String> command = new ArrayList<>();
            command.add(SANDBOX_EXEC.toString());
            command.add("-p");
            command.add(generated.profile());
            command.addAll(generated.definitions());
            command.add("--");
            command.add(executable.toString());
            command.addAll(request.command().arguments());
            SandboxResult result =
                    ProcessExecutor.execute(command, policy, environment, request.standardInput());
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
}
