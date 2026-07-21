package io.github.sandboxdemo.platform.linux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapSandboxRunnerTest {

    @TempDir Path root;

    @Test
    void permitsWorkspaceWriteAndBlocksSiblingWrite() throws Exception {
        Assumptions.assumeTrue(
                Files.isExecutable(Path.of("/usr/bin/bwrap"))
                        || Files.isExecutable(Path.of("/bin/bwrap")),
                "bubblewrap is not installed");
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        SandboxPolicy policy = SandboxPolicy.builder(workspace).network(NetworkPolicy.DENY).build();
        LinuxBubblewrapSandboxRunner runner = new LinuxBubblewrapSandboxRunner();

        Path allowed = workspace.resolve("allowed.txt");
        SandboxResult allowedResult =
                runner.execute(SandboxRequest.of(policy, shellWrite(allowed)));
        assertTrue(allowedResult.successful(), allowedResult.stderrUtf8());
        assertTrue(Files.exists(allowed));

        Path blocked = outside.resolve("blocked.txt");
        SandboxResult blockedResult =
                runner.execute(SandboxRequest.of(policy, shellWrite(blocked)));
        assertFalse(blockedResult.successful());
        assertFalse(Files.exists(blocked));

        SandboxResult stdinResult =
                runner.execute(
                        SandboxRequest.builder(workspace, CommandSpec.of("/bin/sh", "-c", "cat"))
                                .standardInputUtf8("sdk-stdin")
                                .network(NetworkPolicy.DENY)
                                .build());
        assertTrue(stdinResult.successful(), stdinResult.stderrUtf8());
        assertTrue(stdinResult.stdoutUtf8().contains("sdk-stdin"));
    }

    private static CommandSpec shellWrite(Path target) {
        return CommandSpec.of("/bin/sh", "-c", "printf test > \"$1\"", "test", target.toString());
    }
}
