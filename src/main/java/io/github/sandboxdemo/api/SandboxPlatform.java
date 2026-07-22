package io.github.sandboxdemo.api;

import io.github.sandboxdemo.core.OperatingSystem;

/** Operating-system family selected by the SDK. */
public enum SandboxPlatform {
    WINDOWS,
    LINUX,
    MACOS;

    /** Returns the platform of the current JVM. */
    public static SandboxPlatform current() {
        switch (OperatingSystem.current()) {
            case WINDOWS:
                return WINDOWS;
            case LINUX:
                return LINUX;
            case MACOS:
                return MACOS;
            default:
                throw new IllegalStateException("unsupported operating system");
        }
    }
}
