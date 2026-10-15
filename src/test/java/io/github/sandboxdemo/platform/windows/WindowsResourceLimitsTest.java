package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import org.junit.jupiter.api.Test;

class WindowsResourceLimitsTest {
    @Test
    void requestCanOnlyTightenHostCeilings() {
        assertEquals(64, WindowsRestrictedProcessLauncher.effectiveMemoryLimit(64, 128));
        assertEquals(32, WindowsRestrictedProcessLauncher.effectiveMemoryLimit(64, 32));
        assertEquals(64, WindowsRestrictedProcessLauncher.effectiveMemoryLimit(64, 0));
        assertEquals(2, WindowsRestrictedProcessLauncher.effectiveProcessLimit(2, 5));
        assertEquals(1, WindowsRestrictedProcessLauncher.effectiveProcessLimit(2, 1));
        assertEquals(2, WindowsRestrictedProcessLauncher.effectiveProcessLimit(2, 0));
    }

    @Test
    void bothWindowsBackendsRejectCpuDiskAndVmRequirements() throws Exception {
        for (SandboxRunner runner :
                new SandboxRunner[] {
                    new WindowsRestrictedTokenSandboxRunner(),
                    new WindowsProductionSandboxRunner(
                            java.nio.file.Paths.get("target/synthetic-home"))
                }) {
            assertTrue(
                    runner.supportsResourceLimits(
                            SandboxResourceLimits.builder().memoryBytes(1).processes(1).build()));
            assertFalse(
                    runner.supportsResourceLimits(
                            SandboxResourceLimits.builder().diskBytes(1).build()));
            assertFalse(
                    runner.supportsResourceLimits(
                            SandboxResourceLimits.builder()
                                    .cpuTime(java.time.Duration.ofMillis(1))
                                    .build()));
            assertEquals(SandboxIsolationLevel.OS_PROCESS, runner.isolationLevel());
        }
    }
}
