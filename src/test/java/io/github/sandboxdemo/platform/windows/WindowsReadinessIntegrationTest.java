package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.TestEvidence;
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import io.github.sandboxdemo.sdk.SandboxExecutionBudget;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIf("io.github.sandboxdemo.platform.windows.WindowsNativeTestSupport#enabled")
class WindowsReadinessIntegrationTest {
    @TempDir Path workspace;

    private SandboxClient client() {
        return WindowsNativeTestSupport.client();
    }

    private SandboxRequest.Builder request() {
        return SandboxRequest.builder(
                        workspace,
                        Paths.get(
                                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                                        "System32",
                                        "cmd.exe")
                                .toString())
                .arguments("/d", "/s", "/c", "echo unexpected>caller-target-marker")
                .readPolicy(ReadPolicy.HOST)
                .network(NetworkPolicy.ALLOW)
                .timeout(Duration.ofSeconds(120));
    }

    @Test
    void policyProbeRunsOnlyTrustedCommandAndReportsUnknownHostCleanup() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        SandboxPolicyReadiness readiness =
                client().readiness(
                                request()
                                        .standardInputUtf8("echo unexpected")
                                        .environment("ComSpec", "synthetic-invalid-command")
                                        .environment("PATH", workspace.toString())
                                        .stdoutConsumer(bytes -> callbacks.incrementAndGet())
                                        .stderrConsumer(bytes -> callbacks.incrementAndGet())
                                        .build());
        assertTrue(readiness.ready(), readiness.toString());
        assertEquals(0, callbacks.get());
        assertFalse(Files.exists(workspace.resolve("caller-target-marker")));
        assertEquals(SandboxCleanupState.CONFIRMED, readiness.cleanup().processes());
        assertEquals(SandboxCleanupState.UNKNOWN, readiness.cleanup().hostResources());
        assertTrue(
                readiness
                        .backendInfo()
                        .backendVersion()
                        .get()
                        .contains(
                                WindowsNativeTestSupport.defaultBackend()
                                        ? "windows-native"
                                        : "worker-protocol 7"));
        TestEvidence.record(readiness);
    }

    @Test
    void strictReadRemainsUnsupportedBeforeAnyTrustedWorkerOrTargetIsLaunched() throws Exception {
        SandboxPolicyReadiness readiness =
                client().readiness(request().readPolicy(ReadPolicy.DECLARED_ONLY).build());
        assertFalse(readiness.ready());
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, readiness.error().get().code());
        assertEquals(SandboxPhase.ADMISSION, readiness.error().get().phase());
        assertEquals(SandboxCleanupState.NOT_REQUIRED, readiness.cleanup().processes());
        assertFalse(Files.exists(workspace.resolve("caller-target-marker")));
    }

    @Test
    void invalidPathPreparationDoesNotLeakAdmissionCapacity() throws Exception {
        SandboxExecutionBudget budget = SandboxExecutionBudget.bounded(1);
        SandboxClient client = WindowsNativeTestSupport.client(budget);
        SandboxRequest invalid =
                SandboxRequest.of(
                        SandboxPolicy.builder(workspace.resolve("absent"))
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.ALLOW)
                                .build(),
                        request().build().command());
        for (int i = 0; i < 3; i++) {
            SandboxPolicyReadiness readiness = client.readiness(invalid);
            assertFalse(readiness.ready());
            assertEquals(SandboxErrorCode.POLICY_INVALID, readiness.error().get().code());
            assertEquals(SandboxCleanupState.NOT_REQUIRED, readiness.cleanup().hostResources());
            assertEquals(0, budget.inUse());
        }
        assertTrue(client.readiness(request().build()).ready());
        assertEquals(0, budget.inUse());
        assertFalse(Files.exists(workspace.resolve("caller-target-marker")));
    }
}
