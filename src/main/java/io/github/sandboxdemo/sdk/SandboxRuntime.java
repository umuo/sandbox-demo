package io.github.sandboxdemo.sdk;

import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxCapability;
import io.github.sandboxdemo.api.SandboxCapabilitySupport;
import io.github.sandboxdemo.api.SandboxEnforcement;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxGuarantee;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.platform.linux.LinuxBubblewrapSandboxRunner;
import io.github.sandboxdemo.platform.macos.MacOsSeatbeltSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsRestrictedTokenSandboxRunner;
import io.github.sandboxdemo.platform.windows.WindowsSandboxSetup;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** SDK lifecycle and readiness facade. Setup operations never run implicitly during execution. */
public final class SandboxRuntime {

    private SandboxRuntime() {}

    /** Returns the runtime home used only by the optional dedicated-account Windows backend. */
    public static Path defaultWindowsHome() {
        return WindowsSandboxSetup.defaultHome();
    }

    /** Installs or upgrades the optional Windows runtime. The calling process must be elevated. */
    public static void installWindows(Path home) throws SandboxException, InterruptedException {
        WindowsSandboxSetup.install(home);
    }

    /** Removes a matching optional Windows runtime. The calling process must be elevated. */
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
        return status(platform, null, defaultBackendName(platform));
    }

    static SandboxCapabilities capabilities(SandboxPlatform platform, String backendName) {
        Set<ReadPolicy> readPolicies =
                platform == SandboxPlatform.WINDOWS
                        ? io.github.sandboxdemo.core.Java8.setOf(ReadPolicy.HOST)
                        : io.github.sandboxdemo.core.Java8.setOf(
                                ReadPolicy.DECLARED_ONLY, ReadPolicy.HOST);
        boolean setupWindowsBackend =
                platform == SandboxPlatform.WINDOWS
                        && "windows-dedicated-user-restricted-token".equals(backendName);
        Set<NetworkPolicy> networkPolicies =
                platform == SandboxPlatform.WINDOWS && !setupWindowsBackend
                        ? io.github.sandboxdemo.core.Java8.setOf(NetworkPolicy.ALLOW)
                        : io.github.sandboxdemo.core.Java8.setOf(
                                NetworkPolicy.ALLOW, NetworkPolicy.DENY);
        Set<DeletionPolicy> deletionPolicies =
                platform == SandboxPlatform.WINDOWS
                        ? io.github.sandboxdemo.core.Java8.setOf(
                                DeletionPolicy.ALLOW, DeletionPolicy.DENY)
                        : io.github.sandboxdemo.core.Java8.setOf(DeletionPolicy.ALLOW);
        return new SandboxCapabilities(
                platform,
                backendName,
                readPolicies,
                networkPolicies,
                deletionPolicies,
                setupWindowsBackend,
                platform == SandboxPlatform.WINDOWS
                        ? SandboxEnforcement.PARTIAL
                        : SandboxEnforcement.FULL,
                capabilityDimensions(platform, backendName));
    }

    private static Map<SandboxCapability, SandboxCapabilitySupport> capabilityDimensions(
            SandboxPlatform platform, String backendName) {
        Map<SandboxCapability, SandboxCapabilitySupport> result =
                new EnumMap<>(SandboxCapability.class);
        boolean linux = platform == SandboxPlatform.LINUX && "linux-bubblewrap".equals(backendName);
        boolean mac = platform == SandboxPlatform.MACOS && "macos-seatbelt".equals(backendName);
        boolean dedicated =
                platform == SandboxPlatform.WINDOWS
                        && "windows-dedicated-user-restricted-token".equals(backendName);
        boolean windows =
                platform == SandboxPlatform.WINDOWS
                        && (dedicated || "windows-restricted-token".equals(backendName));
        if (!linux && !mac && !windows) {
            return result;
        }
        for (SandboxCapability capability : SandboxCapability.values()) {
            result.put(
                    capability, new SandboxCapabilitySupport(SandboxGuarantee.UNSUPPORTED, "none"));
        }
        result.put(
                SandboxCapability.STRICT_READ,
                new SandboxCapabilitySupport(
                        windows ? SandboxGuarantee.UNSUPPORTED : SandboxGuarantee.KERNEL,
                        linux ? "mount-namespace" : mac ? "seatbelt-with-runtime-roots" : "none"));
        result.put(
                SandboxCapability.WRITE_ISOLATION,
                new SandboxCapabilitySupport(
                        SandboxGuarantee.KERNEL,
                        linux ? "mount-namespace" : mac ? "seatbelt" : "restricted-token-and-acl"));
        result.put(
                SandboxCapability.NETWORK_DENY,
                new SandboxCapabilitySupport(
                        windows && !dedicated
                                ? SandboxGuarantee.UNSUPPORTED
                                : SandboxGuarantee.KERNEL,
                        linux
                                ? "seccomp"
                                : mac
                                        ? "seatbelt"
                                        : dedicated ? "dedicated-account-firewall" : "none"));
        result.put(
                SandboxCapability.PROCESS_TREE_TERMINATION,
                new SandboxCapabilitySupport(
                        mac ? SandboxGuarantee.BEST_EFFORT : SandboxGuarantee.KERNEL,
                        linux
                                ? "pid-namespace"
                                : mac ? "java-descendant-observation" : "job-object"));
        result.put(
                SandboxCapability.TEMP_CLEANUP,
                new SandboxCapabilitySupport(SandboxGuarantee.BEST_EFFORT, "filesystem-delete"));
        if (windows) {
            result.put(
                    SandboxCapability.MEMORY_LIMIT,
                    new SandboxCapabilitySupport(
                            SandboxGuarantee.KERNEL, "job-object-host-configured"));
            result.put(
                    SandboxCapability.PROCESS_LIMIT,
                    new SandboxCapabilitySupport(
                            SandboxGuarantee.KERNEL, "job-object-host-configured"));
        }
        return result;
    }

    static SandboxRuntimeStatus status(
            SandboxPlatform platform, Path windowsHome, String backendName) {
        SandboxCapabilities capabilities = capabilities(platform, backendName);
        try {
            switch (platform) {
                case WINDOWS:
                    if ("windows-dedicated-user-restricted-token".equals(backendName)) {
                        if (windowsHome == null) {
                            throw new IllegalArgumentException(
                                    "dedicated-account Windows backend requires a runtime home");
                        }
                        WindowsSandboxSetup.verify(windowsHome);
                        return new SandboxRuntimeStatus(
                                capabilities, true, "Windows production runtime verified");
                    }
                    return new SandboxRuntimeStatus(
                            capabilities, true, WindowsRestrictedTokenSandboxRunner.probeBackend());
                case LINUX:
                    return new SandboxRuntimeStatus(
                            capabilities, true, LinuxBubblewrapSandboxRunner.probeBackend());
                case MACOS:
                    return new SandboxRuntimeStatus(
                            capabilities, true, MacOsSeatbeltSandboxRunner.probeBackend());
                default:
                    throw new IllegalStateException("unsupported platform: " + platform);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new SandboxRuntimeStatus(capabilities, false, "readiness check interrupted");
        } catch (Exception error) {
            String message =
                    error instanceof SandboxException
                            ? error.getMessage()
                            : "Sandbox readiness failed";
            return new SandboxRuntimeStatus(
                    capabilities,
                    false,
                    message == null || io.github.sandboxdemo.core.Java8.isBlank(message)
                            ? error.getClass().getSimpleName()
                            : message);
        }
    }

    private static String defaultBackendName(SandboxPlatform platform) {
        switch (platform) {
            case WINDOWS:
                return "windows-restricted-token";
            case LINUX:
                return "linux-bubblewrap";
            case MACOS:
                return "macos-seatbelt";
            default:
                throw new IllegalStateException("unsupported platform: " + platform);
        }
    }
}
