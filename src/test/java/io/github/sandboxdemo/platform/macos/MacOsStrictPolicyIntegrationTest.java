package io.github.sandboxdemo.platform.macos;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** 受支持宿主的强制验收：后端异常必须失败，禁止转换成跳过。 */
@EnabledOnOs(OS.MAC)
@EnabledIfEnvironmentVariable(named = "SANDBOX_MACOS_STRICT_ACCEPTANCE", matches = "1")
class MacOsStrictPolicyIntegrationTest {
    @TempDir Path root;

    private SandboxPolicy policy(Path workspace, NetworkPolicy network) {
        return SandboxPolicy.builder(workspace)
                .readPolicy(ReadPolicy.DECLARED_ONLY)
                .network(network)
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void validationDoesNotRunTargetAndStrictShellRuns() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path marker = workspace.resolve("target-marker");
        SandboxClient client = SandboxClient.create();
        SandboxRequest request =
                SandboxRequest.of(
                        policy(workspace, NetworkPolicy.DENY),
                        CommandSpec.of(
                                "/bin/sh",
                                "-c",
                                "printf target > \"$1\"",
                                "probe",
                                marker.toString()));
        client.validate(request);
        assertFalse(Files.exists(marker));
        SandboxResult result =
                client.execute(
                        SandboxRequest.of(
                                request.policy(),
                                CommandSpec.of("/bin/sh", "-c", "printf sdk-probe")));
        assertTrue(result.successful(), result.toString());
        assertEquals("sdk-probe", result.stdoutUtf8());
    }

    @Test
    void strictReadAndWriteBlockExternalSyntheticCanary() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path outside = root.resolve("canary");
        Files.write(outside, "synthetic-canary".getBytes(StandardCharsets.UTF_8));
        SandboxClient client = SandboxClient.create();
        SandboxPolicy policy = policy(workspace, NetworkPolicy.DENY);
        SandboxResult read =
                client.execute(
                        SandboxRequest.of(policy, CommandSpec.of("/bin/cat", outside.toString())));
        assertFalse(read.successful());
        assertEquals(0, read.stdout().length);
        SandboxResult write =
                client.execute(
                        SandboxRequest.of(
                                policy,
                                CommandSpec.of(
                                        "/bin/sh",
                                        "-c",
                                        "printf changed > \"$1\"",
                                        "probe",
                                        outside.toString())));
        assertFalse(write.successful());
        assertEquals(
                "synthetic-canary",
                new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
        SandboxResult allowed =
                client.execute(
                        SandboxRequest.of(
                                policy,
                                CommandSpec.of(
                                        "/bin/sh", "-c", "printf allowed > local && cat local")));
        assertEquals("allowed", allowed.stdoutUtf8());
        assertTrue(allowed.successful());
    }

    @Test
    void deniedNetworkDoesNotConnectToLoopbackWhileAllowedDoes() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        try (ServerSocket server =
                new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(4000);
            ExecutorService listener = Executors.newSingleThreadExecutor();
            try {
                Future<?> accepted =
                        listener.submit(
                                () -> {
                                    try (Socket socket = server.accept()) {
                                        socket.getOutputStream()
                                                .write(
                                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                                                .getBytes(
                                                                        StandardCharsets.US_ASCII));
                                    } catch (java.io.IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                CommandSpec curl =
                        CommandSpec.of(
                                "/usr/bin/curl",
                                "--noproxy",
                                "*",
                                "--connect-timeout",
                                "1",
                                "--max-time",
                                "2",
                                "-s",
                                "http://127.0.0.1:" + server.getLocalPort());
                SandboxClient client = SandboxClient.create();
                SandboxResult denied =
                        client.execute(
                                SandboxRequest.of(policy(workspace, NetworkPolicy.DENY), curl));
                assertFalse(denied.successful());
                assertFalse(accepted.isDone(), "DENY connected to the loopback server");
                SandboxResult allowed =
                        client.execute(
                                SandboxRequest.of(policy(workspace, NetworkPolicy.ALLOW), curl));
                assertTrue(allowed.successful(), allowed.toString());
                assertEquals("ok", allowed.stdoutUtf8());
                accepted.get(4, TimeUnit.SECONDS);
            } finally {
                listener.shutdownNow();
            }
        }
    }

    @Test
    void declaredJdkCanRunWithoutHostReadMode() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path javaHome = Paths.get(System.getProperty("java.home")).toRealPath();
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .readableRoot(javaHome)
                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                        .network(NetworkPolicy.DENY)
                        .timeout(Duration.ofSeconds(5))
                        .build();
        Path source = workspace.resolve("Hello.java");
        Files.write(
                source,
                ("public class Hello { public static void main(String[] args) { "
                                + "System.out.print(\"sdk-jdk\"); } }")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals(
                0,
                javax.tools.ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", workspace.toString(), source.toString()));
        SandboxResult result =
                SandboxClient.create()
                        .execute(
                                SandboxRequest.of(
                                        policy,
                                        CommandSpec.of(
                                                javaHome.resolve("bin/java").toString(),
                                                "-cp",
                                                workspace.toString(),
                                                "Hello")));
        assertTrue(result.successful(), result.toString());
        assertEquals("sdk-jdk", result.stdoutUtf8());
    }
}
