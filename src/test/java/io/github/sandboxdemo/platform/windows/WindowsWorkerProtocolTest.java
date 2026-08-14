package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

            Path resultFile = root.resolve("result.bin");
            SandboxResult result =
                    new SandboxResult(
                            7,
                            false,
                            new byte[] {1, 2},
                            new byte[] {3},
                            false,
                            true,
                            Duration.ofMillis(9));
            WindowsWorkerProtocol.writeResult(resultFile, result, null);
            SandboxResult decodedResult = WindowsWorkerProtocol.readResult(resultFile);
            assertEquals(result.exitCode(), decodedResult.exitCode());
            assertArrayEquals(result.stdout(), decodedResult.stdout());
            assertArrayEquals(result.stderr(), decodedResult.stderr());
            assertEquals(result.stderrTruncated(), decodedResult.stderrTruncated());
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

        assertTrue(error.getMessage().startsWith("failed to read Windows worker request ("));
        assertTrue(error.getCause() instanceof java.io.IOException);
    }
}
