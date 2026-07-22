package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.platform.linux.LinuxBubblewrapSandboxRunner;
import io.github.sandboxdemo.platform.macos.MacOsSeatbeltSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsProductionSandboxRunner;
import java.nio.file.Path;

/** Factory pattern: selects an OS-specific enforcement strategy once at startup. */
public final class DefaultSandboxRunnerFactory {

    private DefaultSandboxRunnerFactory() {}

    public static SandboxRunner create() {
        return create(null);
    }

    public static SandboxRunner create(Path windowsHome) {
        switch (OperatingSystem.current()) {
            case LINUX:
                return new LinuxBubblewrapSandboxRunner();
            case MACOS:
                return new MacOsSeatbeltSandboxRunner();
            case WINDOWS:
                return windowsHome == null
                        ? new WindowsProductionSandboxRunner()
                        : new WindowsProductionSandboxRunner(windowsHome);
            default:
                throw new IllegalStateException("unsupported operating system");
        }
    }
}
