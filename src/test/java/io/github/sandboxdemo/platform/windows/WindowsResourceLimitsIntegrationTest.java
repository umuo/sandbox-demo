package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SANDBOX_RUN_RESOURCE_INTEGRATION", matches = "1")
class WindowsResourceLimitsIntegrationTest {
    @TempDir Path workspace;

    private SandboxCompletion execute(String mode, SandboxResourceLimits limits) throws Exception {
        Path fixture = Paths.get("target/resource-probe.exe").toAbsolutePath();
        assertTrue(
                Files.isRegularFile(fixture),
                "compile the bounded resource probe before native tests");
        SandboxClient client = WindowsNativeTestSupport.client();
        SandboxRequest request =
                SandboxRequest.builder(workspace, fixture.toString())
                        .argument(mode)
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.ALLOW)
                        .readableRoot(fixture.getParent())
                        .resourceLimits(limits)
                        .requiredIsolation(SandboxIsolationLevel.OS_PROCESS)
                        .timeout(Duration.ofSeconds(120))
                        .build();
        SandboxPolicyReadiness readiness = client.readiness(request);
        assertTrue(readiness.ready(), readiness.toString());
        assertEquals(limits, readiness.resourceLimits());
        ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<Runnable>(1),
                        new ThreadPoolExecutor.AbortPolicy());
        SandboxExecution execution = null;
        try {
            execution = client.start(request, workers);
            SandboxCompletion completion = execution.await(Duration.ofSeconds(125));
            assertTrue(completion.result().isPresent(), completion.toString());
            assertTrue(
                    completion.result().get().completeSuccessful(),
                    () ->
                            completion.toString()
                                    + "; synthetic fixture stderr="
                                    + completion.result().get().stderrUtf8());
            assertEquals(
                    SandboxCleanupState.CONFIRMED,
                    completion.cleanup().processes(),
                    completion.toString());
            assertEquals(
                    SandboxCleanupState.CONFIRMED,
                    completion.cleanup().temporaryFiles(),
                    completion.toString());
            assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().hostResources());
            return completion;
        } finally {
            if (execution != null) {
                execution.cancel();
            }
            workers.shutdown();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void aggregateCommittedMemoryLimitDeniesBoundedAllocationAndReclaimsJob() throws Exception {
        assertEquals(
                "allocation-allowed",
                execute("memory", SandboxResourceLimits.none()).result().get().stdoutUtf8());
        assertEquals(
                "allocation-denied",
                execute(
                                "memory",
                                SandboxResourceLimits.builder()
                                        .memoryBytes(96L * 1024 * 1024)
                                        .build())
                        .result()
                        .get()
                        .stdoutUtf8());
    }

    @Test
    void activeProcessLimitBlocksOneChildAndReclaimsJob() throws Exception {
        assertEquals(
                "child-allowed",
                execute("process", SandboxResourceLimits.none()).result().get().stdoutUtf8());
        assertEquals(
                "child-denied",
                execute("process", SandboxResourceLimits.builder().processes(1).build())
                        .result()
                        .get()
                        .stdoutUtf8());
    }
}
