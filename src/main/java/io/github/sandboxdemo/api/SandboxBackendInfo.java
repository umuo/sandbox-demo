package io.github.sandboxdemo.api;

import java.util.Objects;
import java.util.Optional;

/**
 * Snapshot of runtime versions. Version strings are explicit metadata, never default diagnostics.
 */
public final class SandboxBackendInfo {
    private final SandboxPlatform platform;
    private final String backendName,
            backendVersion,
            osName,
            osVersion,
            architecture,
            javaVersion,
            javaVendor,
            sdkVersion;

    public SandboxBackendInfo(
            SandboxPlatform platform,
            String backendName,
            String backendVersion,
            String osName,
            String osVersion,
            String architecture,
            String javaVersion,
            String javaVendor,
            String sdkVersion) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.backendName = Objects.requireNonNull(backendName, "backendName");
        this.backendVersion = backendVersion;
        this.osName = osName;
        this.osVersion = osVersion;
        this.architecture = architecture;
        this.javaVersion = javaVersion;
        this.javaVendor = javaVendor;
        this.sdkVersion = sdkVersion;
    }

    public static SandboxBackendInfo current(
            SandboxPlatform platform, String backendName, String backendVersion) {
        return new SandboxBackendInfo(
                platform,
                backendName,
                backendVersion,
                property("os.name"),
                property("os.version"),
                property("os.arch"),
                property("java.version"),
                property("java.vendor"),
                SandboxBackendInfo.class.getPackage().getImplementationVersion());
    }

    private static String property(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException denied) {
            return null;
        }
    }

    public SandboxPlatform platform() {
        return platform;
    }

    public String backendName() {
        return backendName;
    }

    public Optional<String> backendVersion() {
        return Optional.ofNullable(backendVersion);
    }

    public Optional<String> osName() {
        return Optional.ofNullable(osName);
    }

    public Optional<String> osVersion() {
        return Optional.ofNullable(osVersion);
    }

    public Optional<String> architecture() {
        return Optional.ofNullable(architecture);
    }

    public Optional<String> javaVersion() {
        return Optional.ofNullable(javaVersion);
    }

    public Optional<String> javaVendor() {
        return Optional.ofNullable(javaVendor);
    }

    public Optional<String> sdkVersion() {
        return Optional.ofNullable(sdkVersion);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SandboxBackendInfo)) {
            return false;
        }
        SandboxBackendInfo that = (SandboxBackendInfo) other;
        return platform == that.platform
                && backendName.equals(that.backendName)
                && Objects.equals(backendVersion, that.backendVersion)
                && Objects.equals(osName, that.osName)
                && Objects.equals(osVersion, that.osVersion)
                && Objects.equals(architecture, that.architecture)
                && Objects.equals(javaVersion, that.javaVersion)
                && Objects.equals(javaVendor, that.javaVendor)
                && Objects.equals(sdkVersion, that.sdkVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                platform,
                backendName,
                backendVersion,
                osName,
                osVersion,
                architecture,
                javaVersion,
                javaVendor,
                sdkVersion);
    }

    @Override
    public String toString() {
        return "SandboxBackendInfo[platform="
                + platform
                + ", backendVersionAvailable="
                + (backendVersion != null)
                + "]";
    }
}
