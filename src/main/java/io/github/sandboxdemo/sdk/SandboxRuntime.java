package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.platform.linux.LinuxBubblewrapSandboxRunner;
import io.github.sandboxdemo.platform.macos.MacOsSeatbeltSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsSandboxSetup;
import java.nio.file.Path;
import java.util.Set;

/** SDK lifecycle and readiness facade. Setup operations never run implicitly during execution. */
public final class SandboxRuntime {

    private SandboxRuntime() {}

    /** Returns the default Windows runtime home. */
    public static Path defaultWindowsHome() {
        return WindowsSandboxSetup.defaultHome();
    }

    /** Installs or upgrades the Windows runtime. The calling process must be elevated. */
    public static void installWindows(Path home) throws SandboxException, InterruptedException {
        WindowsSandboxSetup.install(home);
    }

    /** Removes a matching Windows runtime. The calling process must be elevated. */
    public static void uninstallWindows(Path home) throws SandboxException, InterruptedException {
        WindowsSandboxSetup.uninstall(home);
    }

    /** Verifies Windows metadata, account identity and offline firewall configuration. */
    public static void verifyWindows(Path home) throws SandboxException, InterruptedException {
        WindowsSandboxSetup.verify(home);
    }

    /** Returns static capabilities for the current platform. */
    public static SandboxCapabilities capabilities() {
        SandboxPlatform platform = SandboxPlatform.current();
        return capabilities(platform, defaultBackendName(platform));
    }

    /** Performs a non-persistent readiness probe for the current platform. */
    public static SandboxRuntimeStatus status() {
        SandboxPlatform platform = SandboxPlatform.current();
        return status(platform, defaultWindowsHome(), defaultBackendName(platform));
    }

    static SandboxCapabilities capabilities(SandboxPlatform platform, String backendName) {
        Set<ReadPolicy> readPolicies =
                platform == SandboxPlatform.WINDOWS
                        ? Set.of(ReadPolicy.HOST)
                        : Set.of(ReadPolicy.DECLARED_ONLY, ReadPolicy.HOST);
        return new SandboxCapabilities(
                platform,
                backendName,
                readPolicies,
                Set.of(NetworkPolicy.ALLOW, NetworkPolicy.DENY),
                platform == SandboxPlatform.WINDOWS);
    }

    static SandboxRuntimeStatus status(
            SandboxPlatform platform, Path windowsHome, String backendName) {
        SandboxCapabilities capabilities = capabilities(platform, backendName);
        try {
            return switch (platform) {
                case WINDOWS -> {
                    WindowsSandboxSetup.verify(windowsHome);
                    yield new SandboxRuntimeStatus(capabilities, true, "Windows runtime verified");
                }
                case LINUX -> {
                    yield new SandboxRuntimeStatus(
                            capabilities, true, LinuxBubblewrapSandboxRunner.probeBackend());
                }
                case MACOS ->
                        new SandboxRuntimeStatus(
                                capabilities, true, MacOsSeatbeltSandboxRunner.probeBackend());
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new SandboxRuntimeStatus(capabilities, false, "readiness check interrupted");
        } catch (Exception error) {
            String message = error.getMessage();
            return new SandboxRuntimeStatus(
                    capabilities,
                    false,
                    message == null || message.isBlank()
                            ? error.getClass().getSimpleName()
                            : message);
        }
    }

    private static String defaultBackendName(SandboxPlatform platform) {
        return switch (platform) {
            case WINDOWS -> "windows-dedicated-user-restricted-token";
            case LINUX -> "linux-bubblewrap";
            case MACOS -> "macos-seatbelt";
        };
    }
}
