package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.core.PathPolicyValidator;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsWorkerProtocolTest {

    @TempDir Path root;

    @Test
    void requestAndResultRoundTripPreservesSecurityPolicy() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Path readable = Files.createDirectory(root.resolve("input"));
        Path executable = Files.createFile(root.resolve("tool.exe"));
        ValidatedPolicy policy =
                PathPolicyValidator.validate(
                        SandboxPolicy.builder(workspace)
                                .readableRoot(readable)
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.DENY)
                                .deletion(DeletionPolicy.DENY)
                                .onOutputLimit(
                                        io.github.sandboxdemo.api.OutputLimitAction.TERMINATE)
                                .maxCombinedOutputBytes(19)
                                .outputCallbackMode(
                                        io.github.sandboxdemo.api.OutputCallbackMode.RETAINED)
                                .timeout(Duration.ofSeconds(3))
                                .build());
        try {
            Path requestFile = root.resolve("request.bin");
            WindowsWorkerProtocol.WorkerRequest request =
                    new WindowsWorkerProtocol.WorkerRequest(
                            "S-1-5-21-1",
                            executable,
                            io.github.sandboxdemo.core.Java8.listOf("one", "two"),
                            "stdin-data".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            policy,
                            io.github.sandboxdemo.core.Java8.mapOf("LANG", "C"),
                            io.github.sandboxdemo.core.Java8.listOf("S-1-5-21-2"));
            WindowsWorkerProtocol.writeRequest(requestFile, request);
            WindowsWorkerProtocol.WorkerRequest decoded =
                    WindowsWorkerProtocol.readRequest(requestFile);

            assertEquals(request.expectedUserSid(), decoded.expectedUserSid());
            assertEquals(request.arguments(), decoded.arguments());
            assertArrayEquals(request.standardInput(), decoded.standardInput());
            assertEquals(policy.readableRoots(), decoded.policy().readableRoots());
            assertEquals(policy.readPolicy(), decoded.policy().readPolicy());
            assertEquals(policy.deletionPolicy(), decoded.policy().deletionPolicy());
            assertEquals(policy.onOutputLimit(), decoded.policy().onOutputLimit());
            assertEquals(
                    policy.maxCombinedOutputBytes(), decoded.policy().maxCombinedOutputBytes());
            assertEquals(policy.outputCallbackMode(), decoded.policy().outputCallbackMode());

            Path resultFile = root.resolve("result.bin");
            SandboxResult result =
                    new SandboxResult(
                            7,
                            false,
                            new byte[] {1, 2},
                            new byte[] {3},
                            false,
                            true,
                            Duration.ofMillis(9),
                            true);
            WindowsWorkerProtocol.writeResult(resultFile, result, null);
            SandboxResult decodedResult = WindowsWorkerProtocol.readResult(resultFile);
            assertEquals(result.exitCode(), decodedResult.exitCode());
            assertArrayEquals(result.stdout(), decodedResult.stdout());
            assertArrayEquals(result.stderr(), decodedResult.stderr());
            assertEquals(result.stderrTruncated(), decodedResult.stderrTruncated());
            assertEquals(result, decodedResult);
            byte[] staleRequest = Files.readAllBytes(requestFile);
            java.nio.ByteBuffer.wrap(staleRequest).putInt(4, WindowsWorkerProtocol.VERSION - 1);
            Files.write(requestFile, staleRequest);
            assertThrows(
                    SandboxException.class, () -> WindowsWorkerProtocol.readRequest(requestFile));
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    @Test
    void requestReadFailurePreservesTheOperatingSystemCause() throws Exception {
        Path directoryInsteadOfRequest = Files.createDirectory(root.resolve("request.bin"));

        SandboxException error =
                assertThrows(
                        SandboxException.class,
                        () -> WindowsWorkerProtocol.readRequest(directoryInsteadOfRequest));

        assertTrue(error.debugMessage().startsWith("failed to read Windows worker request ("));
        assertTrue(error.debugCause() instanceof java.io.IOException);
    }

    @Test
    void structuredWorkerErrorsPreserveCodesAndPhasesWithoutDefaultDiagnostics() throws Exception {
        Path file = root.resolve("failure.bin");
        String marker = "synthetic-secret-worker";
        for (io.github.sandboxdemo.api.SandboxErrorCode code :
                io.github.sandboxdemo.api.SandboxErrorCode.values()) {
            SandboxException original =
                    new SandboxException(
                            code, io.github.sandboxdemo.api.SandboxPhase.OUTPUT, marker);
            WindowsWorkerProtocol.writeFailure(file, original);
            SandboxException decoded =
                    assertThrows(
                            SandboxException.class, () -> WindowsWorkerProtocol.readResult(file));
            assertEquals(code, decoded.code());
            assertEquals(original.phase(), decoded.phase());
            assertEquals(marker, decoded.debugMessage());
            assertFalse(decoded.toString().contains(marker));
            assertEquals(null, decoded.getCause());
        }
    }

    @Test
    void malformedWorkerErrorCodeFailsClosed() throws Exception {
        Path file = root.resolve("invalid-failure.bin");
        WindowsWorkerProtocol.writeFailure(
                file,
                new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "diagnostic"));
        String binary =
                new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
        Files.write(
                file,
                binary.replace("POLICY_INVALID", "INVALID_POLICY")
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        SandboxException failure =
                assertThrows(SandboxException.class, () -> WindowsWorkerProtocol.readResult(file));
        assertEquals(
                io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED, failure.code());
        assertEquals(io.github.sandboxdemo.api.SandboxPhase.OUTPUT, failure.phase());
    }
}
