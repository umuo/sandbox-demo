package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPolicyValidationTest {
    @TempDir Path workspace;

    private SandboxClient client(SandboxRunner runner) {
        SandboxCapabilities caps =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        runner.backendName(),
                        Collections.singleton(ReadPolicy.DECLARED_ONLY),
                        Collections.singleton(NetworkPolicy.DENY),
                        false);
        return SandboxClient.builder()
                .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, "test"))
                .build();
    }

    @Test
    void legacyRunnerCannotSilentlyClaimActualPolicyReadiness() {
        SandboxRunner legacy =
                new SandboxRunner() {
                    public String backendName() {
                        return "legacy";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("target started");
                    }
                };
        SandboxException error =
                assertThrows(
                        SandboxException.class,
                        () ->
                                client(legacy)
                                        .validate(
                                                SandboxRequest.builder(workspace, "/bin/true")
                                                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                                                        .network(NetworkPolicy.DENY)
                                                        .build()));
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, error.code());
        assertEquals(SandboxPhase.PROBE, error.phase());
    }

    @Test
    void admissionRejectsBeforeProbeAndValidationNeverCallsExecute() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "probe";
                    }

                    public void validate(SandboxRequest request) {
                        probes.incrementAndGet();
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("target started");
                    }
                };
        SandboxClient client = client(runner);
        SandboxException error =
                assertThrows(
                        SandboxException.class,
                        () ->
                                client.validate(
                                        SandboxRequest.builder(workspace, "/bin/true")
                                                .readPolicy(ReadPolicy.DECLARED_ONLY)
                                                .network(NetworkPolicy.DENY)
                                                .network(NetworkPolicy.ALLOW)
                                                .build()));
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, error.code());
        assertEquals(SandboxPhase.ADMISSION, error.phase());
        assertEquals(0, probes.get());
        client.validate(
                SandboxRequest.builder(workspace, "/bin/true")
                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                        .network(NetworkPolicy.DENY)
                        .network(NetworkPolicy.DENY)
                        .build());
        assertEquals(1, probes.get());
    }

    @Test
    void legacyBackendRejectsNewOutputConfigurationBeforeLaunchingAnything() {
        AtomicInteger launches = new AtomicInteger();
        SandboxRunner legacy =
                new SandboxRunner() {
                    public String backendName() {
                        return "legacy-output";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        launches.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        SandboxException error =
                assertThrows(
                        SandboxException.class,
                        () ->
                                client(legacy)
                                        .execute(
                                                SandboxRequest.builder(workspace, "/bin/true")
                                                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                                                        .network(NetworkPolicy.DENY)
                                                        .onOutputLimit(OutputLimitAction.TERMINATE)
                                                        .build()));
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, error.code());
        assertEquals(SandboxPhase.ADMISSION, error.phase());
        assertEquals(0, launches.get());
    }
}
