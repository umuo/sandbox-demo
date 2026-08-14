package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxEnforcement;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SandboxClientTest {

    @Test
    void runtimeReportsPlatformEnforcementCompleteness() {
        assertEquals(
                SandboxEnforcement.PARTIAL,
                SandboxRuntime.capabilities(SandboxPlatform.WINDOWS, "windows-test").enforcement());
        assertEquals(
                SandboxEnforcement.FULL,
                SandboxRuntime.capabilities(SandboxPlatform.LINUX, "linux-test").enforcement());
        assertEquals(
                SandboxEnforcement.FULL,
                SandboxRuntime.capabilities(SandboxPlatform.MACOS, "macos-test").enforcement());
    }

    @Test
    void setupFreeWindowsBackendAdvertisesOnlyUnrestrictedNetwork() {
        SandboxCapabilities setupFree =
                SandboxRuntime.capabilities(SandboxPlatform.WINDOWS, "windows-restricted-token");
        SandboxCapabilities production =
                SandboxRuntime.capabilities(
                        SandboxPlatform.WINDOWS, "windows-dedicated-user-restricted-token");

        assertTrue(setupFree.supports(NetworkPolicy.ALLOW));
        assertFalse(setupFree.supports(NetworkPolicy.DENY));
        assertFalse(setupFree.installationRequired());
        assertTrue(production.supports(NetworkPolicy.ALLOW));
        assertTrue(production.supports(NetworkPolicy.DENY));
        assertTrue(production.installationRequired());
        assertTrue(setupFree.supports(DeletionPolicy.DENY));
        assertTrue(production.supports(DeletionPolicy.DENY));
        assertFalse(
                SandboxRuntime.capabilities(SandboxPlatform.LINUX, "linux-bubblewrap")
                        .supports(DeletionPolicy.DENY));
    }

    @Test
    void facadeDelegatesTheImmutableRequestToTheSelectedStrategy() throws Exception {
        AtomicReference<SandboxRequest> observed = new AtomicReference<>();
        SandboxResult expected =
                new SandboxResult(
                        0,
                        false,
                        "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        new byte[0],
                        false,
                        false,
                        Duration.ofMillis(2));
        SandboxRunner fake =
                new SandboxRunner() {
                    @Override
                    public String backendName() {
                        return "test-strategy";
                    }

                    @Override
                    public SandboxResult execute(SandboxRequest request) {
                        observed.set(request);
                        return expected;
                    }
                };
        SandboxCapabilities capabilities =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        fake.backendName(),
                        io.github.sandboxdemo.core.Java8.setOf(ReadPolicy.DECLARED_ONLY),
                        io.github.sandboxdemo.core.Java8.setOf(NetworkPolicy.DENY),
                        false);
        SandboxClient client =
                SandboxClient.builder()
                        .backend(
                                fake,
                                capabilities,
                                () -> new SandboxRuntimeStatus(capabilities, true, "test ready"))
                        .build();
        Path workspace = java.nio.file.Paths.get("target", "sdk-client-workspace").toAbsolutePath();
        SandboxPolicy policy = SandboxPolicy.builder(workspace).build();
        SandboxRequest request = SandboxRequest.of(policy, CommandSpec.of("/bin/true"));

        SandboxResult actual = client.execute(request);

        assertSame(request, observed.get());
        assertSame(expected, actual);
        assertEquals("test-strategy", client.backendName());
        assertEquals("test-strategy", client.capabilities().backendName());
        assertEquals(SandboxEnforcement.FULL, client.capabilities().enforcement());
        assertTrue(client.capabilities().supports(policy.networkPolicy()));
    }

    @Test
    void facadeRejectsUnsupportedPolicyBeforeCallingBackend() {
        AtomicBoolean executed = new AtomicBoolean();
        SandboxRunner fake =
                new SandboxRunner() {
                    @Override
                    public String backendName() {
                        return "deny-only";
                    }

                    @Override
                    public SandboxResult execute(SandboxRequest request) {
                        executed.set(true);
                        throw new AssertionError("unsupported policy reached backend");
                    }
                };
        SandboxCapabilities capabilities =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        fake.backendName(),
                        io.github.sandboxdemo.core.Java8.setOf(ReadPolicy.DECLARED_ONLY),
                        io.github.sandboxdemo.core.Java8.setOf(NetworkPolicy.DENY),
                        false);
        SandboxClient client =
                SandboxClient.builder()
                        .backend(
                                fake,
                                capabilities,
                                () -> new SandboxRuntimeStatus(capabilities, true, "test ready"))
                        .build();
        Path workspace = java.nio.file.Paths.get("target", "sdk-client-workspace").toAbsolutePath();
        SandboxRequest unsupported =
                SandboxRequest.builder(workspace, "/bin/true").network(NetworkPolicy.ALLOW).build();

        SandboxBackendUnavailableException error =
                assertThrows(
                        SandboxBackendUnavailableException.class,
                        () -> client.execute(unsupported));

        assertTrue(error.getMessage().contains("does not support network policy ALLOW"));
        assertFalse(executed.get());
    }

    @Test
    void facadeRejectsUnsupportedDeletionPolicyBeforeCallingBackend() {
        AtomicBoolean executed = new AtomicBoolean();
        SandboxRunner fake =
                new SandboxRunner() {
                    @Override
                    public String backendName() {
                        return "allow-delete-only";
                    }

                    @Override
                    public SandboxResult execute(SandboxRequest request) {
                        executed.set(true);
                        throw new AssertionError("unsupported policy reached backend");
                    }
                };
        SandboxCapabilities capabilities =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        fake.backendName(),
                        io.github.sandboxdemo.core.Java8.setOf(ReadPolicy.DECLARED_ONLY),
                        io.github.sandboxdemo.core.Java8.setOf(NetworkPolicy.DENY),
                        false);
        SandboxClient client =
                SandboxClient.builder()
                        .backend(
                                fake,
                                capabilities,
                                () -> new SandboxRuntimeStatus(capabilities, true, "test ready"))
                        .build();
        Path workspace = java.nio.file.Paths.get("target", "sdk-client-workspace").toAbsolutePath();
        SandboxRequest unsupported =
                SandboxRequest.builder(workspace, "/bin/true")
                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                        .network(NetworkPolicy.DENY)
                        .deletion(DeletionPolicy.DENY)
                        .build();

        SandboxBackendUnavailableException error =
                assertThrows(
                        SandboxBackendUnavailableException.class,
                        () -> client.execute(unsupported));

        assertTrue(error.getMessage().contains("does not support deletion policy DENY"));
        assertFalse(executed.get());
    }
}
