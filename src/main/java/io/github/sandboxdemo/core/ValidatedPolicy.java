package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class ValidatedPolicy {

    private final Path workingDirectory;
    private final List<Path> readableRoots;
    private final List<Path> writableRoots;
    private final List<Path> protectedPaths;
    private final Path privateTempDirectory;
    private final NetworkPolicy networkPolicy;
    private final ReadPolicy readPolicy;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final boolean allowPathSearch;

    public ValidatedPolicy(
            Path workingDirectory,
            List<Path> readableRoots,
            List<Path> writableRoots,
            List<Path> protectedPaths,
            Path privateTempDirectory,
            NetworkPolicy networkPolicy,
            ReadPolicy readPolicy,
            Duration timeout,
            int maxOutputBytes,
            boolean allowPathSearch) {
        this.workingDirectory = workingDirectory;
        this.readableRoots = readableRoots;
        this.writableRoots = writableRoots;
        this.protectedPaths = protectedPaths;
        this.privateTempDirectory = privateTempDirectory;
        this.networkPolicy = networkPolicy;
        this.readPolicy = readPolicy;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.allowPathSearch = allowPathSearch;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public List<Path> readableRoots() {
        return readableRoots;
    }

    public List<Path> writableRoots() {
        return writableRoots;
    }

    public List<Path> protectedPaths() {
        return protectedPaths;
    }

    public Path privateTempDirectory() {
        return privateTempDirectory;
    }

    public NetworkPolicy networkPolicy() {
        return networkPolicy;
    }

    public ReadPolicy readPolicy() {
        return readPolicy;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    public boolean allowPathSearch() {
        return allowPathSearch;
    }
}
