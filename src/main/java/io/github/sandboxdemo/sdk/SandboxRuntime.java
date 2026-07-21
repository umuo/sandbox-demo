package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.platform.windows.WindowsSandboxSetup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** SDK lifecycle and readiness facade. Setup operations never run implicitly during execution. */
public final class SandboxRuntime {

    private static final Path MACOS_LAUNCHER = Path.of("/usr/bin/sandbox-exec");

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

    /** Performs a side-effect-free readiness check for the current platform. */
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
                    Path bwrap = findBubblewrap();
                    yield new SandboxRuntimeStatus(
                            capabilities, true, "bubblewrap launcher: " + bwrap);
                }
                case MACOS ->
                        new SandboxRuntimeStatus(
                                capabilities,
                                Files.isExecutable(MACOS_LAUNCHER),
                                Files.isExecutable(MACOS_LAUNCHER)
                                        ? "Seatbelt launcher: " + MACOS_LAUNCHER
                                        : "Seatbelt launcher is unavailable: " + MACOS_LAUNCHER);
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

    private static Path findBubblewrap() {
        String override = System.getenv("SANDBOX_BWRAP");
        List<Path> candidates =
                override == null || override.isBlank()
                        ? List.of(Path.of("/usr/bin/bwrap"), Path.of("/bin/bwrap"))
                        : List.of(Path.of(override));
        return candidates.stream()
                .filter(Path::isAbsolute)
                .filter(Files::isExecutable)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "bubblewrap is unavailable; install it or set SANDBOX_BWRAP"));
    }

    private static String defaultBackendName(SandboxPlatform platform) {
        return switch (platform) {
            case WINDOWS -> "windows-dedicated-user-restricted-token";
            case LINUX -> "linux-bubblewrap";
            case MACOS -> "macos-seatbelt";
        };
    }
}
