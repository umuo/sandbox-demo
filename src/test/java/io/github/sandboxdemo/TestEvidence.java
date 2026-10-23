package io.github.sandboxdemo;

import io.github.sandboxdemo.api.SandboxBackendInfo;
import io.github.sandboxdemo.api.SandboxPolicyReadiness;
import java.nio.file.*;
import java.util.Properties;

/**
 * Explicit CI evidence contains selected versions and enums, never paths, argv, environment or
 * diagnostics.
 */
public final class TestEvidence {
    private TestEvidence() {}

    public static void record(SandboxPolicyReadiness readiness) throws Exception {
        SandboxBackendInfo info = readiness.backendInfo();
        Properties values = new Properties();
        values.setProperty("backend", info.backendName());
        values.setProperty("backend.version", info.backendVersion().orElse("UNKNOWN"));
        values.setProperty("sdk.version", info.sdkVersion().orElse("UNKNOWN (source classes)"));
        values.setProperty("os.name", info.osName().orElse("UNKNOWN"));
        values.setProperty("os.version", info.osVersion().orElse("UNKNOWN"));
        values.setProperty("os.arch", info.architecture().orElse("UNKNOWN"));
        values.setProperty("java.version", info.javaVersion().orElse("UNKNOWN"));
        values.setProperty("java.vendor", info.javaVendor().orElse("UNKNOWN"));
        values.setProperty(
                "scope", "per-policy trusted probe; not a VM/complete cleanup guarantee");
        values.setProperty("read.policy", readiness.readPolicy().name());
        values.setProperty("network.policy", readiness.networkPolicy().name());
        values.setProperty("probe.ready", Boolean.toString(readiness.ready()));
        if (info.platform() == io.github.sandboxdemo.api.SandboxPlatform.WINDOWS) {
            values.setProperty(
                    "windows.token.elevated",
                    Boolean.toString(
                            com.sun.jna.platform.win32.Advapi32Util.isCurrentProcessElevated()));
        }
        Path directory = Paths.get("target", "backend-evidence");
        Files.createDirectories(directory);
        try (java.io.OutputStream out =
                Files.newOutputStream(directory.resolve(info.platform().name() + ".xml"))) {
            values.storeToXML(out, "Explicit native preflight evidence", "UTF-8");
        }
    }
}
