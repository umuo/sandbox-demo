package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsRestrictedTokenSandboxRunnerTest {

    @TempDir Path root;

    @BeforeEach
    void requireUnelevatedTestProcess() {
        Assumptions.assumeFalse(
                com.sun.jna.platform.win32.Advapi32Util.isCurrentProcessElevated(),
                "setup-free Windows backend intentionally rejects elevated processes");
    }

    @Test
    void defaultClientAndReadinessProbeRequireNoSetup() throws Exception {
        assertTrue(SandboxClient.create().backendName().equals("windows-restricted-token"));
        assertTrue(
                WindowsRestrictedTokenSandboxRunner.probeBackend()
                        .contains("setup-free Windows restricted-token probe passed"));
    }

    @Test
    void permitsWorkspaceWriteAndBlocksSiblingWrite() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace with spaces"));
        Path outside = Files.createDirectory(root.resolve("outside with spaces"));
        SandboxPolicy policy = SandboxPolicy.builder(workspace).build();
        WindowsRestrictedTokenSandboxRunner runner = new WindowsRestrictedTokenSandboxRunner();

        Path allowed = workspace.resolve("allowed.txt");
        SandboxResult allowedResult = runner.execute(SandboxRequest.of(policy, cmdWrite(allowed)));
        assertTrue(allowedResult.successful(), failureDetails(allowedResult));
        assertTrue(Files.exists(allowed));

        Path blocked = outside.resolve("blocked.txt");
        SandboxResult blockedResult = runner.execute(SandboxRequest.of(policy, cmdWrite(blocked)));
        assertFalse(blockedResult.successful());
        assertFalse(Files.exists(blocked));
    }

    @Test
    void permitsCreateAndModifyButBlocksDeleteAndRename() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("no-delete-workspace"));
        Path existing = Files.write(workspace.resolve("existing.txt"), new byte[] {1});
        Path created = workspace.resolve("created.txt");
        Path renamed = workspace.resolve("renamed.txt");
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace).deletion(DeletionPolicy.DENY).build();
        WindowsRestrictedTokenSandboxRunner runner = new WindowsRestrictedTokenSandboxRunner();

        SandboxResult writeResult =
                runner.execute(
                        SandboxRequest.of(
                                policy,
                                cmd(
                                        "echo modified>\""
                                                + existing
                                                + "\" & echo created>\""
                                                + created
                                                + "\"")));
        assertTrue(writeResult.successful(), failureDetails(writeResult));
        assertTrue(Files.exists(created));

        SandboxResult deleteResult =
                runner.execute(SandboxRequest.of(policy, cmd("del /f /q \"" + existing + "\"")));
        assertFalse(deleteResult.successful());
        assertTrue(Files.exists(existing));

        SandboxResult renameResult =
                runner.execute(
                        SandboxRequest.of(
                                policy, cmd("move /y \"" + existing + "\" \"" + renamed + "\"")));
        assertFalse(renameResult.successful());
        assertTrue(Files.exists(existing));
        assertFalse(Files.exists(renamed));
    }

    private static CommandSpec cmdWrite(Path target) {
        return cmd("echo test>\"" + target + "\"");
    }

    private static CommandSpec cmd(String command) {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return CommandSpec.of(
                java.nio.file.Paths.get(systemRoot, "System32", "cmd.exe").toString(),
                "/d",
                "/s",
                "/c",
                command);
    }

    private static String failureDetails(SandboxResult result) {
        return "exitCode="
                + result.exitCode()
                + ", timedOut="
                + result.timedOut()
                + ", stderr="
                + result.stderrUtf8();
    }
}
