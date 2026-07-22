package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class WindowsRestrictedTokenSandboxRunnerTest {

    @TempDir Path root;

    @Test
    void permitsWorkspaceWriteAndBlocksSiblingWrite() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace with spaces"));
        Path outside = Files.createDirectory(root.resolve("outside with spaces"));
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .network(NetworkPolicy.ALLOW)
                        .readPolicy(ReadPolicy.HOST)
                        .build();
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

    private static CommandSpec cmdWrite(Path target) {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return CommandSpec.of(
                java.nio.file.Paths.get(systemRoot, "System32", "cmd.exe").toString(),
                "/d",
                "/s",
                "/c",
                "echo test>\"" + target + "\"");
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
