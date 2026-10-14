package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SandboxDiagnosticsTest {
    @Test
    void normalExceptionRenderingDoesNotExposeDiagnosticOrCause() {
        String secret = "synthetic-secret-argv-env-path";
        Throwable cause = new java.io.IOException(secret);
        SandboxException error =
                new SandboxException(
                        SandboxErrorCode.START_FAILED, SandboxPhase.START, secret, cause);
        StringWriter log = new StringWriter();
        error.printStackTrace(new PrintWriter(log));

        assertFalse(error.getMessage().contains(secret));
        assertFalse(error.toString().contains(secret));
        assertFalse(log.toString().contains(secret));
        assertNull(error.getCause());
        assertEquals(secret, error.debugMessage());
        assertSame(cause, error.debugCause());
        assertEquals(SandboxErrorCode.START_FAILED, error.code());
        assertEquals(SandboxPhase.START, error.phase());
    }

    @Test
    void legacyExceptionsRemainConservativeAndRedacted() {
        SandboxException legacy = new SandboxException("synthetic-secret");
        assertEquals(SandboxErrorCode.UNKNOWN, legacy.code());
        assertEquals(SandboxPhase.UNKNOWN, legacy.phase());
        assertFalse(legacy.getMessage().contains("synthetic-secret"));
        SandboxBackendUnavailableException unavailable =
                new SandboxBackendUnavailableException("synthetic-secret");
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, unavailable.code());
    }

    @Test
    void resultRenderingContainsLengthsInsteadOfReversibleOutput() {
        byte[] secret = "synthetic-secret".getBytes(StandardCharsets.UTF_8);
        SandboxResult result =
                new SandboxResult(0, false, secret, secret, false, false, Duration.ZERO);
        assertFalse(result.toString().contains("synthetic-secret"));
        assertFalse(result.toString().contains(java.util.Arrays.toString(secret)));
        assertTrue(result.toString().contains("stdoutBytes=" + secret.length));
        assertArrayEquals(secret, result.stdout());
    }
}
