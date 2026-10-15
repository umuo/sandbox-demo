package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.ExecutionContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxReadinessTest {
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
                .backend(
                        runner,
                        caps,
                        () -> {
                            throw new AssertionError("basic status is not policy evidence");
                        })
                .build();
    }

    private SandboxRequest request() {
        return SandboxRequest.builder(workspace, "/synthetic/tool")
                .readPolicy(ReadPolicy.DECLARED_ONLY)
                .network(NetworkPolicy.DENY)
                .build();
    }

    private abstract static class Fake implements SandboxRunner {
        public String backendName() {
            return "readiness-fake";
        }

        public SandboxResult execute(SandboxRequest r) {
            throw new AssertionError("target started");
        }
    }

    @Test
    void successfulProbeDoesNotClaimOpaqueBackendCleanupOrNativeVersion() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        SandboxPolicyReadiness result =
                client(
                                new Fake() {
                                    public void validate(SandboxRequest r) {
                                        probes.incrementAndGet();
                                    }
                                })
                        .readiness(request());
        assertTrue(result.ready());
        assertEquals(1, probes.get());
        assertFalse(result.error().isPresent());
        assertEquals(ReadPolicy.DECLARED_ONLY, result.readPolicy());
        assertEquals(NetworkPolicy.DENY, result.networkPolicy());
        assertTrue(result.cleanup().workerFinished());
        assertFalse(result.cleanup().confirmed());
        assertFalse(result.backendInfo().backendVersion().isPresent());
        assertEquals(System.getProperty("java.version"), result.backendInfo().javaVersion().get());
    }

    @Test
    void unsupportedPolicyRejectsBeforeProbeOrMetadataCollection() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Fake runner =
                new Fake() {
                    public void validate(SandboxRequest r) {
                        calls.incrementAndGet();
                    }

                    public SandboxBackendInfo backendInfo() {
                        calls.incrementAndGet();
                        throw new AssertionError();
                    }
                };
        SandboxPolicyReadiness result =
                client(runner)
                        .readiness(
                                SandboxRequest.builder(workspace, "/synthetic/tool")
                                        .readPolicy(ReadPolicy.HOST)
                                        .network(NetworkPolicy.DENY)
                                        .build());
        assertFalse(result.ready());
        assertEquals(0, calls.get());
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, result.error().get().code());
        assertEquals(SandboxPhase.ADMISSION, result.error().get().phase());
        assertTrue(result.cleanup().confirmed());
    }

    @Test
    void structuredErrorsAndDefaultStringsHideDiagnosticData() throws Exception {
        String marker = "synthetic-secret-readiness";
        Fake runner =
                new Fake() {
                    public void validate(SandboxRequest r) throws SandboxException {
                        throw new SandboxException(
                                SandboxErrorCode.POLICY_INVALID,
                                SandboxPhase.PREPARATION,
                                marker,
                                new IllegalStateException(marker));
                    }
                };
        SandboxPolicyReadiness result = client(runner).readiness(request());
        assertFalse(result.ready());
        assertFalse(result.toString().contains(marker));
        SandboxException error = result.error().get();
        assertFalse(error.toString().contains(marker));
        assertNull(error.getCause());
        assertTrue(error.debugMessage().contains(marker));
        assertTrue(error.debugCause().toString().contains(marker));
        assertEquals(
                error.code(),
                assertThrows(SandboxException.class, () -> client(runner).validate(request()))
                        .code());
    }

    @Test
    void unexpectedProbeFailureIsClassifiedWithoutLeakingItsCause() throws Exception {
        SandboxPolicyReadiness result =
                client(
                                new Fake() {
                                    public void validate(SandboxRequest r) {
                                        throw new IllegalStateException(
                                                "synthetic-secret-unexpected");
                                    }
                                })
                        .readiness(request());
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, result.error().get().code());
        assertEquals(SandboxPhase.PROBE, result.error().get().phase());
        assertFalse(result.error().get().getMessage().contains("synthetic-secret"));
        assertNotNull(result.error().get().debugCause());
    }

    @Test
    void linkageFailureDuringExecutionIsAStableBackendError() {
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "native-link";
                    }

                    public SandboxResult execute(SandboxRequest r) {
                        throw new UnsatisfiedLinkError("synthetic-secret-library-path");
                    }
                };
        SandboxException error =
                assertThrows(SandboxException.class, () -> client(runner).execute(request()));
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, error.code());
        assertEquals(SandboxPhase.PREPARATION, error.phase());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("synthetic-secret"));
    }

    @Test
    void deadlineAlsoIncludesProbePreparation() throws Exception {
        Fake runner =
                new Fake() {
                    public void validate(SandboxRequest r) throws InterruptedException {
                        Thread.sleep(40);
                    }
                };
        SandboxPolicyReadiness result =
                client(runner)
                        .readiness(
                                SandboxRequest.builder(workspace, "/synthetic/tool")
                                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                                        .network(NetworkPolicy.DENY)
                                        .timeout(Duration.ofMillis(10))
                                        .build());
        assertFalse(result.ready());
        assertEquals(SandboxErrorCode.TIMEOUT, result.error().get().code());
        assertEquals(SandboxPhase.PROBE, result.error().get().phase());
        assertTrue(result.cleanup().workerFinished());
    }

    @Test
    void knownCleanupFailureCannotProduceReady() throws Exception {
        Fake runner =
                new Fake() {
                    public void validate(SandboxRequest r) {
                        ExecutionContext.current().managedBackend();
                        ExecutionContext.current()
                                .track(ExecutionContext.Resource.TEMP)
                                .complete(SandboxCleanupState.FAILED);
                    }
                };
        SandboxPolicyReadiness result = client(runner).readiness(request());
        assertFalse(result.ready());
        assertEquals(SandboxErrorCode.CLEANUP_FAILED, result.error().get().code());
        assertEquals(SandboxPhase.CLEANUP, result.error().get().phase());
    }

    @Test
    void metadataAndLegacyStatusDoNotPrintVersionDiagnostics() {
        String marker = "synthetic-secret-version";
        SandboxBackendInfo info =
                new SandboxBackendInfo(
                        SandboxPlatform.current(),
                        marker,
                        marker,
                        marker,
                        marker,
                        marker,
                        marker,
                        marker,
                        marker);
        assertFalse(info.toString().contains(marker));
        assertEquals(marker, info.backendVersion().get());
        SandboxCapabilities caps = SandboxRuntime.capabilities();
        SandboxRuntimeStatus status =
                new SandboxRuntimeStatus(
                        caps,
                        false,
                        marker,
                        info,
                        new SandboxException(
                                SandboxErrorCode.BACKEND_UNAVAILABLE, SandboxPhase.PROBE, marker));
        assertFalse(status.toString().contains(marker));
        assertEquals(marker, status.diagnostic());
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, status.error().get().code());
        assertFalse(new SandboxRuntimeStatus(caps, false, marker).ready());
    }

    @Test
    void unexpectedRuntimeAndStatusErrorsHaveSafeDefaultMessages() {
        String marker = "synthetic-secret-runtime";
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "runtime-fake";
                    }

                    public SandboxResult execute(SandboxRequest r) {
                        throw new IllegalStateException(marker);
                    }
                };
        SandboxException error =
                assertThrows(SandboxException.class, () -> client(runner).execute(request()));
        assertEquals(SandboxErrorCode.UNKNOWN, error.code());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(marker));
        assertTrue(error.debugCause().toString().contains(marker));
        SandboxRuntimeStatus status =
                SandboxClient.builder()
                        .backend(
                                runner,
                                new SandboxCapabilities(
                                        SandboxPlatform.current(),
                                        runner.backendName(),
                                        Collections.singleton(ReadPolicy.DECLARED_ONLY),
                                        Collections.singleton(NetworkPolicy.DENY),
                                        false),
                                () -> {
                                    throw new IllegalStateException(marker);
                                })
                        .build()
                        .status();
        assertFalse(status.ready());
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, status.error().get().code());
        assertFalse(status.toString().contains(marker));
    }
}
