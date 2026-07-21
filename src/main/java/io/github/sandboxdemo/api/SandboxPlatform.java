package io.github.sandboxdemo.api;

import io.github.sandboxdemo.core.OperatingSystem;

/** Operating-system family selected by the SDK. */
public enum SandboxPlatform {
    WINDOWS,
    LINUX,
    MACOS;

    /** Returns the platform of the current JVM. */
    public static SandboxPlatform current() {
        return switch (OperatingSystem.current()) {
            case WINDOWS -> WINDOWS;
            case LINUX -> LINUX;
            case MACOS -> MACOS;
        };
    }
}
