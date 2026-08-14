package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.platform.linux.LinuxBubblewrapSandboxRunner;
import io.github.sandboxdemo.platform.macos.MacOsSeatbeltSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsProductionSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsRestrictedTokenSandboxRunner;
import java.nio.file.Path;

/** Factory pattern: selects an OS-specific enforcement strategy once at startup. */
public final class DefaultSandboxRunnerFactory {

    private DefaultSandboxRunnerFactory() {}

    public static SandboxRunner create() {
        return create(null);
    }

    public static SandboxRunner create(Path windowsHome) {
        return create(OperatingSystem.current(), windowsHome);
    }

    static SandboxRunner create(OperatingSystem operatingSystem, Path windowsHome) {
        switch (operatingSystem) {
            case LINUX:
                return new LinuxBubblewrapSandboxRunner();
            case MACOS:
                return new MacOsSeatbeltSandboxRunner();
            case WINDOWS:
                return windowsHome == null
                        ? new WindowsRestrictedTokenSandboxRunner()
                        : new WindowsProductionSandboxRunner(windowsHome);
            default:
                throw new IllegalStateException("unsupported operating system");
        }
    }
}
