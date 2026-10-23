package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SANDBOX_WINDOWS_PRODUCTION_TEST", matches = "1")
class WindowsElevatedRejectionTest {
    @TempDir Path root;

    @Test
    void elevatedHostCannotProbeOrExecuteDefaultBackend() throws Exception {
        assertTrue(
                com.sun.jna.platform.win32.Advapi32Util.isCurrentProcessElevated(),
                "production CI installation path must run elevated");
        SandboxException probe =
                assertThrows(
                        SandboxException.class, WindowsRestrictedTokenSandboxRunner::probeBackend);
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, probe.code());
        SandboxRequest request =
                SandboxRequest.builder(
                                root,
                                Paths.get(System.getenv("SystemRoot"), "System32", "cmd.exe")
                                        .toString())
                        .arguments("/d", "/s", "/c", "echo unexpected>target-marker")
                        .build();
        SandboxException execution =
                assertThrows(
                        SandboxException.class,
                        () -> new WindowsRestrictedTokenSandboxRunner().execute(request));
        assertEquals(SandboxErrorCode.BACKEND_UNAVAILABLE, execution.code());
        assertFalse(Files.exists(root.resolve("target-marker")));
    }
}
