package io.github.sandboxdemo.platform.macos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
class MacOsSeatbeltSandboxRunnerTest {

    @TempDir Path root;

    @Test
    void permitsWorkspaceWriteAndBlocksSiblingWrite() throws Exception {
        requireNonNestedTestHost();
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path protectedDirectory = Files.createDirectory(workspace.resolve("protected"));
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .protect(protectedDirectory)
                        .network(NetworkPolicy.DENY)
                        .timeout(Duration.ofSeconds(5))
                        .build();
        MacOsSeatbeltSandboxRunner runner = new MacOsSeatbeltSandboxRunner();

        Path allowed = workspace.resolve("allowed.txt");
        SandboxResult allowedResult =
                executeOrSkip(runner, SandboxRequest.of(policy, shellWrite(allowed)));
        assertTrue(
                allowedResult.successful(),
                "exit=" + allowedResult.exitCode() + ", stderr=" + allowedResult.stderrUtf8());
        assertTrue(Files.exists(allowed));

        Path blocked = outside.resolve("blocked.txt");
        SandboxResult blockedResult =
                runner.execute(SandboxRequest.of(policy, shellWrite(blocked)));
        assertFalse(blockedResult.successful());
        assertFalse(Files.exists(blocked));

        Path protectedFile = protectedDirectory.resolve("blocked.txt");
        SandboxResult protectedResult =
                runner.execute(SandboxRequest.of(policy, shellWrite(protectedFile)));
        assertFalse(protectedResult.successful());
        assertFalse(Files.exists(protectedFile));

        SandboxResult stdinResult =
                runner.execute(
                        SandboxRequest.builder(workspace, CommandSpec.of("/bin/sh", "-c", "cat"))
                                .standardInputUtf8("sdk-stdin")
                                .network(NetworkPolicy.DENY)
                                .timeout(Duration.ofSeconds(5))
                                .build());
        assertTrue(stdinResult.successful(), stdinResult.stderrUtf8());
        assertTrue(stdinResult.stdoutUtf8().contains("sdk-stdin"));
    }

    @Test
    void terminatesLongRunningCommandAtTimeout() throws Exception {
        requireNonNestedTestHost();
        Path workspace = Files.createDirectory(root.resolve("timeout-workspace"));
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .network(NetworkPolicy.DENY)
                        .timeout(Duration.ofMillis(100))
                        .build();

        SandboxResult result =
                executeOrSkip(
                        new MacOsSeatbeltSandboxRunner(),
                        SandboxRequest.of(policy, CommandSpec.of("/bin/sh", "-c", "sleep 5")));

        assertTrue(result.timedOut());
        assertTrue(result.duration().compareTo(Duration.ofSeconds(3)) < 0);
    }

    private static CommandSpec shellWrite(Path target) {
        return CommandSpec.of("/bin/sh", "-c", "printf test > \"$1\"", "test", target.toString());
    }

    private static SandboxResult executeOrSkip(
            MacOsSeatbeltSandboxRunner runner, SandboxRequest request) throws Exception {
        try {
            SandboxResult result = runner.execute(request);
            if (result.exitCode() == 71) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "macOS Seatbelt could not be nested in the current test host: "
                                + result.stderrUtf8());
            }
            return result;
        } catch (SandboxBackendUnavailableException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, unavailable.getMessage());
            throw unavailable;
        }
    }

    private static void requireNonNestedTestHost() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "1".equals(System.getenv("SANDBOX_RUN_PLATFORM_INTEGRATION")),
                "set SANDBOX_RUN_PLATFORM_INTEGRATION=1 outside an existing App Sandbox");
    }
}
