package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "SANDBOX_RUN_ENVIRONMENT_INTEGRATION", matches = "1")
class SandboxStrictEnvironmentIntegrationTest {
    @TempDir Path parent;

    @Test
    void nativeStrictTargetExcludesParentSecretsAndUsesPrivateHome() throws Exception {
        assertNotNull(
                System.getenv("SANDBOX_SYNTHETIC_PARENT_SECRET"),
                "test requires an injected synthetic parent secret");
        boolean windows = SandboxPlatform.current() == SandboxPlatform.WINDOWS;
        Path tools =
                windows
                        ? Paths.get(
                                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                                        "System32")
                                .toRealPath()
                        : Paths.get("/usr/bin").toRealPath();
        SandboxClient client =
                windows
                        ? SandboxClient.builder()
                                .windowsProductionHome(SandboxRuntime.defaultWindowsHome())
                                .build()
                        : SandboxClient.create();
        SandboxWorkspace workspace = SandboxWorkspace.create(parent);
        try {
            SandboxRequest.Builder builder =
                    workspace
                            .request(windows ? "cmd.exe" : "env")
                            .executionEnvironment(
                                    SandboxEnvironment.strict(tools).allowVariable("APP_VALUE"))
                            .environment("APP_VALUE", "synthetic-value")
                            .allowPathSearch(true)
                            .timeout(Duration.ofSeconds(120));
            if (windows) {
                builder.arguments("/d", "/s", "/c", "set");
            }
            SandboxRequest request = builder.build();
            SandboxPolicyReadiness readiness = client.readiness(request);
            assertTrue(readiness.ready(), readiness.toString());
            SandboxResult result = client.execute(request);
            assertTrue(result.completeSuccessful(), result.toString());
            String env = result.stdoutUtf8();
            assertFalse(env.contains("SANDBOX_SYNTHETIC_PARENT_SECRET="));
            assertFalse(env.contains("JAVA_TOOL_OPTIONS="));
            assertFalse(env.contains("LD_PRELOAD="));
            assertTrue(env.contains("APP_VALUE=synthetic-value"));
            assertTrue(env.contains("PATH=" + tools));
            assertFalse(env.contains("HOME=" + workspace.directory() + System.lineSeparator()));
            // Every SDK execution/probe temp has already been removed, while the host workspace
            // remains.
            try (java.util.stream.Stream<Path> children = Files.list(workspace.temporaryParent())) {
                assertEquals(0, children.count());
            }
            assertTrue(Files.isDirectory(workspace.directory()));
        } finally {
            workspace.close();
        }
        assertEquals(SandboxCleanupState.CONFIRMED, workspace.cleanupState());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void declaredToolchainRemainsReadOnlyInsideActualSandbox() throws Exception {
        Path tools = Files.createDirectory(parent.resolve("trusted-tools"));
        Path canary = Files.write(tools.resolve("canary"), new byte[] {42});
        try (SandboxWorkspace workspace = SandboxWorkspace.create(parent)) {
            SandboxRequest request =
                    workspace
                            .request("/bin/sh")
                            .executionEnvironment(
                                    SandboxEnvironment.strict(Paths.get("/bin"), tools))
                            .arguments(
                                    "-c",
                                    "if printf changed > \"$1\"; then exit 77; fi; printf denied",
                                    "probe",
                                    canary.toString())
                            .timeout(Duration.ofSeconds(30))
                            .build();
            SandboxResult result = SandboxClient.create().execute(request);
            assertTrue(result.completeSuccessful(), result.toString());
            assertEquals("denied", result.stdoutUtf8());
            assertArrayEquals(new byte[] {42}, Files.readAllBytes(canary));
        }
    }
}
