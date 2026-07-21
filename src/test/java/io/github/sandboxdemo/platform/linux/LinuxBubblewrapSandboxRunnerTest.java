package io.github.sandboxdemo.platform.linux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
                executeOrSkip(runner, SandboxRequest.of(policy, shellWrite(allowed)));
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

    @Test
    void seccompAllowsIpSocketsOnlyWhenNetworkIsAllowed() throws Exception {
        Path bash =
                Files.isExecutable(Path.of("/bin/bash"))
                        ? Path.of("/bin/bash")
                        : Path.of("/usr/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(bash), "bash with /dev/tcp is not installed");
        Path workspace = Files.createDirectory(root.resolve("network-workspace"));
        LinuxBubblewrapSandboxRunner runner = new LinuxBubblewrapSandboxRunner();

        try (ServerSocket allowedServer =
                new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            SandboxPolicy allowedPolicy =
                    SandboxPolicy.builder(workspace)
                            .network(NetworkPolicy.ALLOW)
                            .timeout(Duration.ofSeconds(5))
                            .build();
            SandboxResult allowed =
                    executeOrSkip(
                            runner,
                            SandboxRequest.of(
                                    allowedPolicy,
                                    bashTcpWrite(bash, allowedServer.getLocalPort())));
            Assumptions.assumeTrue(
                    allowed.successful(),
                    "this bash does not support a loopback /dev/tcp probe: "
                            + allowed.stderrUtf8());
            allowedServer.setSoTimeout(2_000);
            try (var accepted = allowedServer.accept()) {
                assertTrue(new String(accepted.getInputStream().readAllBytes()).contains("probe"));
            }
        }

        try (ServerSocket deniedServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            SandboxPolicy deniedPolicy =
                    SandboxPolicy.builder(workspace)
                            .network(NetworkPolicy.DENY)
                            .timeout(Duration.ofSeconds(5))
                            .build();
            SandboxResult denied =
                    runner.execute(
                            SandboxRequest.of(
                                    deniedPolicy, bashTcpWrite(bash, deniedServer.getLocalPort())));
            assertFalse(denied.successful(), "seccomp unexpectedly allowed an IP socket");
            deniedServer.setSoTimeout(250);
            org.junit.jupiter.api.Assertions.assertThrows(
                    SocketTimeoutException.class, deniedServer::accept);
        }
    }

    private static CommandSpec shellWrite(Path target) {
        return CommandSpec.of("/bin/sh", "-c", "printf test > \"$1\"", "test", target.toString());
    }

    private static CommandSpec bashTcpWrite(Path bash, int port) {
        return CommandSpec.of(
                bash.toString(),
                "-c",
                "printf probe > /dev/tcp/127.0.0.1/$1",
                "network-probe",
                Integer.toString(port));
    }

    private static SandboxResult executeOrSkip(
            LinuxBubblewrapSandboxRunner runner, SandboxRequest request) throws Exception {
        try {
            return runner.execute(request);
        } catch (SandboxBackendUnavailableException unavailable) {
            Assumptions.assumeTrue(false, unavailable.getMessage());
            throw unavailable;
        }
    }
}
