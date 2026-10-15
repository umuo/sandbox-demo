package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.TestEvidence;
import io.github.sandboxdemo.api.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.MAC, OS.LINUX})
@EnabledIfEnvironmentVariable(named = "SANDBOX_RUN_READINESS_INTEGRATION", matches = "1")
class SandboxReadinessIntegrationTest {
    @TempDir Path workspace;

    @Test
    void actualPolicyProbeNeverRunsTargetInputCallbacksOrCallerLoaderEnvironment()
            throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        Path loader = workspace.resolve("loader.sh");
        Files.write(
                loader, "printf bad > caller-environment-marker".getBytes(StandardCharsets.UTF_8));
        SandboxRequest request =
                SandboxRequest.builder(workspace, "/bin/sh")
                        .arguments("-c", "printf bad > caller-target-marker")
                        .standardInputUtf8("printf bad > caller-input-marker")
                        .environment("ENV", loader.toString())
                        .environment("BASH_ENV", loader.toString())
                        .environment("LD_PRELOAD", loader.toString())
                        .environment("DYLD_INSERT_LIBRARIES", loader.toString())
                        .environment("PATH", workspace.toString())
                        .network(NetworkPolicy.DENY)
                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                        .timeout(Duration.ofSeconds(10))
                        .stdoutConsumer(bytes -> callbacks.incrementAndGet())
                        .stderrConsumer(bytes -> callbacks.incrementAndGet())
                        .build();
        SandboxPolicyReadiness readiness = SandboxClient.create().readiness(request);
        assertTrue(readiness.ready(), readiness.toString());
        assertFalse(Files.exists(workspace.resolve("caller-target-marker")));
        assertFalse(Files.exists(workspace.resolve("caller-input-marker")));
        assertFalse(Files.exists(workspace.resolve("caller-environment-marker")));
        assertEquals(0, callbacks.get());
        assertTrue(readiness.cleanup().workerFinished());
        assertEquals(SandboxCleanupState.CONFIRMED, readiness.cleanup().temporaryFiles());
        assertTrue(readiness.backendInfo().backendVersion().isPresent());
        assertEquals(
                SandboxPlatform.current() == SandboxPlatform.MACOS
                        ? SandboxCleanupState.UNKNOWN
                        : SandboxCleanupState.CONFIRMED,
                readiness.cleanup().processes());
        TestEvidence.record(readiness);
    }

    @Test
    void invalidWorkingDirectoryProducesTypedFailureWithoutTargetSideEffects() throws Exception {
        SandboxPolicyReadiness readiness =
                SandboxClient.create()
                        .readiness(
                                SandboxRequest.builder(workspace.resolve("absent"), "/bin/sh")
                                        .arguments("-c", "exit 99")
                                        .network(NetworkPolicy.DENY)
                                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                                        .build());
        assertFalse(readiness.ready());
        assertEquals(SandboxErrorCode.POLICY_INVALID, readiness.error().get().code());
        assertEquals(SandboxPhase.PREPARATION, readiness.error().get().phase());
    }
}
