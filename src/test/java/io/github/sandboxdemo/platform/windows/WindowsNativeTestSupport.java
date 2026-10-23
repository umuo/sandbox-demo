package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.sdk.SandboxClient;
import java.time.Duration;

/** Explicit backend selection shared by the two native acceptance paths. */
public final class WindowsNativeTestSupport {
    private WindowsNativeTestSupport() {}

    public static boolean defaultBackend() {
        return "1".equals(System.getenv("SANDBOX_WINDOWS_DEFAULT_ACCEPTANCE"));
    }

    public static boolean enabled() {
        return defaultBackend() || "1".equals(System.getenv("SANDBOX_WINDOWS_PRODUCTION_TEST"));
    }

    public static SandboxClient client() {
        if (defaultBackend() && "1".equals(System.getenv("SANDBOX_WINDOWS_PRODUCTION_TEST"))) {
            throw new IllegalStateException("native acceptance must select exactly one backend");
        }
        SandboxClient.Builder builder = SandboxClient.builder().cleanupGrace(Duration.ofSeconds(2));
        if (!defaultBackend()) {
            builder.windowsProductionHome(WindowsSandboxInstallation.defaultHome());
        }
        return builder.build();
    }
}
