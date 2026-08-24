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
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SANDBOX_WINDOWS_PRODUCTION_TEST", matches = "1")
class WindowsProductionSandboxRunnerTest {

    @TempDir Path root;

    @Test
    void streamsOutputFromTheDedicatedWorker() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("streaming-workspace"));
        AtomicReference<String> streamed = new AtomicReference<>("");

        SandboxResult result =
                new WindowsProductionSandboxRunner()
                        .execute(
                                SandboxRequest.builder(workspace, systemExecutable("cmd.exe"))
                                        .arguments("/d", "/s", "/c", "echo dedicated-worker-stream")
                                        .network(NetworkPolicy.DENY)
                                        .readPolicy(ReadPolicy.HOST)
                                        .timeout(Duration.ofSeconds(5))
                                        .outputCharsetAuto()
                                        .stdoutTextConsumer(
                                                text ->
                                                        streamed.updateAndGet(
                                                                value -> value + text))
                                        .build());

        assertSuccessful(result);
        assertTrue(streamed.get().contains("dedicated-worker-stream"));
    }

    @Test
    void enforcesIdentityWritesProtectedPathsEnvironmentAndTimeout() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path protectedDirectory = Files.createDirectory(workspace.resolve("protected"));
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .protect(protectedDirectory)
                        .network(NetworkPolicy.DENY)
                        .readPolicy(ReadPolicy.HOST)
                        .timeout(Duration.ofSeconds(5))
                        .build();
        WindowsProductionSandboxRunner runner = new WindowsProductionSandboxRunner();

        Path allowed = workspace.resolve("allowed.txt");
        SandboxResult allowedResult =
                runner.execute(
                        SandboxRequest.of(
                                policy,
                                cmd(
                                        "echo ok>\""
                                                + allowed
                                                + "\" & \""
                                                + systemExecutable("whoami.exe")
                                                + "\"")));
        assertSuccessful(allowedResult);
        assertTrue(Files.exists(allowed));
        // USERNAME is intentionally absent from the sanitized environment and is not an identity
        // boundary. whoami reads the process access token that the sandbox actually enforces.
        assertTrue(
                allowedResult
                        .stdoutUtf8()
                        .toLowerCase(Locale.ROOT)
                        .contains(
                                WindowsSandboxInstallation.DEFAULT_OFFLINE_USER.toLowerCase(
                                        Locale.ROOT)),
                "unexpected sandbox identity: " + allowedResult.stdoutUtf8());

        Path blocked = outside.resolve("blocked.txt");
        SandboxResult blockedResult =
                runner.execute(SandboxRequest.of(policy, cmd("echo no>\"" + blocked + "\"")));
        assertFalse(blockedResult.successful());
        assertFalse(Files.exists(blocked));

        Path protectedFile = protectedDirectory.resolve("blocked.txt");
        SandboxResult protectedResult =
                runner.execute(SandboxRequest.of(policy, cmd("echo no>\"" + protectedFile + "\"")));
        assertFalse(protectedResult.successful());
        assertFalse(Files.exists(protectedFile));

        SandboxResult environmentResult =
                runner.execute(
                        new SandboxRequest(
                                policy,
                                cmd("if defined SECRET_TEST_VALUE (exit /b 9) else (exit /b 0)"),
                                io.github.sandboxdemo.core.Java8.mapOf()));
        assertSuccessful(environmentResult);

        SandboxResult stdinResult =
                runner.execute(
                        new SandboxRequest(
                                policy,
                                powershell("[Console]::Out.Write([Console]::In.ReadToEnd())"),
                                io.github.sandboxdemo.core.Java8.mapOf(),
                                "sdk-stdin".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertSuccessful(stdinResult);
        assertTrue(stdinResult.stdoutUtf8().contains("sdk-stdin"));

        SandboxPolicy timeoutPolicy =
                SandboxPolicy.builder(workspace)
                        .network(NetworkPolicy.DENY)
                        .readPolicy(ReadPolicy.HOST)
                        .timeout(Duration.ofMillis(200))
                        .build();
        SandboxResult timeoutResult =
                runner.execute(SandboxRequest.of(timeoutPolicy, cmd("ping -n 30 127.0.0.1 >NUL")));
        assertTrue(timeoutResult.timedOut());
    }

    @Test
    void offlineAccountCannotReachNetworkWhenOnlineAccountCan() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("network-workspace"));
        WindowsProductionSandboxRunner runner = new WindowsProductionSandboxRunner();
        String probe =
                "$c=[Net.Sockets.TcpClient]::new();"
                        + "$ok=$c.ConnectAsync('1.1.1.1',443).Wait(5000);"
                        + "$c.Dispose();if(-not $ok){exit 8}";

        SandboxPolicy onlinePolicy =
                SandboxPolicy.builder(workspace)
                        .network(NetworkPolicy.ALLOW)
                        .readPolicy(ReadPolicy.HOST)
                        .timeout(Duration.ofSeconds(8))
                        .build();
        SandboxResult online = runner.execute(SandboxRequest.of(onlinePolicy, powershell(probe)));
        Assumptions.assumeTrue(
                online.successful(),
                "network probe is unavailable on this CI host: " + online.stderrUtf8());

        SandboxPolicy offlinePolicy =
                SandboxPolicy.builder(workspace)
                        .network(NetworkPolicy.DENY)
                        .readPolicy(ReadPolicy.HOST)
                        .timeout(Duration.ofSeconds(8))
                        .build();
        SandboxResult offline = runner.execute(SandboxRequest.of(offlinePolicy, powershell(probe)));
        assertFalse(offline.successful(), "offline sandbox unexpectedly reached the network");
    }

    @Test
    void keepsProjectReadOnlyWhileAllowingOnlyNestedTestSourcesToBeWritten() throws Exception {
        Path project = Files.createDirectory(root.resolve("selective-project"));
        Path writableTests = Files.createDirectories(project.resolve("src/test/java"));
        Path readableAppData = Files.createDirectory(root.resolve("readable-appdata"));
        io.github.sandboxdemo.core.Java8.writeString(
                readableAppData.resolve("input.txt"), "read-ok");
        Path blocked = project.resolve("blocked.txt");
        Path allowed = writableTests.resolve("allowed.txt");
        Path readOnlyDeleteTarget = Files.write(project.resolve("keep.txt"), new byte[] {1});
        Path writableDeleteTarget =
                Files.write(writableTests.resolve("delete-me.txt"), new byte[] {2});

        SandboxPolicy policy =
                SandboxPolicy.builder(project)
                        .readOnlyWorkingDirectory()
                        .readableRoot(readableAppData)
                        .writableRoot(writableTests)
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.ALLOW)
                        .timeout(Duration.ofSeconds(5))
                        .build();
        WindowsProductionSandboxRunner runner = new WindowsProductionSandboxRunner();

        SandboxResult allowedResult =
                runner.execute(SandboxRequest.of(policy, cmd("echo ok>\"" + allowed + "\"")));
        SandboxResult blockedResult =
                runner.execute(SandboxRequest.of(policy, cmd("echo no>\"" + blocked + "\"")));
        SandboxResult readResult =
                runner.execute(
                        SandboxRequest.of(
                                policy,
                                cmd("type \"" + readableAppData.resolve("input.txt") + "\"")));
        SandboxResult blockedDelete =
                runner.execute(
                        SandboxRequest.of(
                                policy, cmd("del /f /q \"" + readOnlyDeleteTarget + "\"")));
        SandboxResult allowedDelete =
                runner.execute(
                        SandboxRequest.of(
                                policy, cmd("del /f /q \"" + writableDeleteTarget + "\"")));

        assertSuccessful(allowedResult);
        assertTrue(Files.exists(allowed));
        assertFalse(blockedResult.successful());
        assertFalse(Files.exists(blocked));
        assertSuccessful(readResult);
        assertTrue(readResult.stdoutUtf8().contains("read-ok"));
        assertFalse(blockedDelete.successful());
        assertTrue(Files.exists(readOnlyDeleteTarget));
        assertSuccessful(allowedDelete);
        assertFalse(Files.exists(writableDeleteTarget));
    }

    private static CommandSpec cmd(String command) {
        return CommandSpec.of(systemExecutable("cmd.exe"), "/d", "/s", "/c", command);
    }

    private static String systemExecutable(String name) {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return java.nio.file.Paths.get(systemRoot, "System32", name).toString();
    }

    private static CommandSpec powershell(String command) {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        return CommandSpec.of(
                java.nio.file.Paths.get(
                                systemRoot,
                                "System32",
                                "WindowsPowerShell",
                                "v1.0",
                                "powershell.exe")
                        .toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                command);
    }

    private static void assertSuccessful(SandboxResult result) {
        assertTrue(
                result.successful(),
                "exitCode="
                        + result.exitCode()
                        + ", timedOut="
                        + result.timedOut()
                        + ", stderr="
                        + result.stderrUtf8());
    }
}
