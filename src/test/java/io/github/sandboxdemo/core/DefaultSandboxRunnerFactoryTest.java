package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsProductionSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsRestrictedTokenSandboxRunner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSandboxRunnerFactoryTest {

    @TempDir Path temp;

    @Test
    void windowsDefaultsToSetupFreeBackendAndKeepsProductionOptIn() {
        SandboxRunner setupFree = DefaultSandboxRunnerFactory.create(OperatingSystem.WINDOWS, null);
        SandboxRunner production =
                DefaultSandboxRunnerFactory.create(OperatingSystem.WINDOWS, temp);

        assertTrue(setupFree instanceof WindowsRestrictedTokenSandboxRunner);
        assertTrue(production instanceof WindowsProductionSandboxRunner);
    }
}
