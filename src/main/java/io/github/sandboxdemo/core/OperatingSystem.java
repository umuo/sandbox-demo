package io.github.sandboxdemo.core;

import java.util.Locale;

public enum OperatingSystem {
    WINDOWS,
    LINUX,
    MACOS;

    public static OperatingSystem current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("linux")) {
            return LINUX;
        }
        throw new IllegalStateException("Unsupported operating system: " + name);
    }
}
