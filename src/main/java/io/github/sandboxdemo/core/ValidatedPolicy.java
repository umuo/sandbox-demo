package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class ValidatedPolicy {

    private final io.github.sandboxdemo.api.SandboxResourceLimits resourceLimits;
    private final io.github.sandboxdemo.api.SandboxIsolationLevel requiredIsolation;
    private final Path workingDirectory;
    private final List<Path> readableRoots;
    private final List<Path> writableRoots;
    private final List<Path> protectedPaths;
    private final Path privateTempDirectory;
    private final NetworkPolicy networkPolicy;
    private final ReadPolicy readPolicy;
    private final DeletionPolicy deletionPolicy;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final boolean allowPathSearch;
    private final io.github.sandboxdemo.api.OutputLimitAction onOutputLimit;
    private final int maxCombinedOutputBytes;
    private final io.github.sandboxdemo.api.OutputCallbackMode outputCallbackMode;

    public ValidatedPolicy(
            Path workingDirectory,
            List<Path> readableRoots,
            List<Path> writableRoots,
            List<Path> protectedPaths,
            Path privateTempDirectory,
            NetworkPolicy networkPolicy,
            ReadPolicy readPolicy,
            DeletionPolicy deletionPolicy,
            Duration timeout,
            int maxOutputBytes,
            boolean allowPathSearch) {
        this(
                workingDirectory,
                readableRoots,
                writableRoots,
                protectedPaths,
                privateTempDirectory,
                networkPolicy,
                readPolicy,
                deletionPolicy,
                timeout,
                maxOutputBytes,
                allowPathSearch,
                io.github.sandboxdemo.api.OutputLimitAction.TRUNCATE,
                0,
                io.github.sandboxdemo.api.OutputCallbackMode.RAW);
    }

    public ValidatedPolicy(
            Path workingDirectory,
            List<Path> readableRoots,
            List<Path> writableRoots,
            List<Path> protectedPaths,
            Path privateTempDirectory,
            NetworkPolicy networkPolicy,
            ReadPolicy readPolicy,
            DeletionPolicy deletionPolicy,
            Duration timeout,
            int maxOutputBytes,
            boolean allowPathSearch,
            io.github.sandboxdemo.api.OutputLimitAction onOutputLimit,
            int maxCombinedOutputBytes,
            io.github.sandboxdemo.api.OutputCallbackMode outputCallbackMode) {
        this(
                workingDirectory,
                readableRoots,
                writableRoots,
                protectedPaths,
                privateTempDirectory,
                networkPolicy,
                readPolicy,
                deletionPolicy,
                timeout,
                maxOutputBytes,
                allowPathSearch,
                onOutputLimit,
                maxCombinedOutputBytes,
                outputCallbackMode,
                io.github.sandboxdemo.api.SandboxResourceLimits.none(),
                io.github.sandboxdemo.api.SandboxIsolationLevel.UNKNOWN);
    }

    public ValidatedPolicy(
            Path workingDirectory,
            List<Path> readableRoots,
            List<Path> writableRoots,
            List<Path> protectedPaths,
            Path privateTempDirectory,
            NetworkPolicy networkPolicy,
            ReadPolicy readPolicy,
            DeletionPolicy deletionPolicy,
            Duration timeout,
            int maxOutputBytes,
            boolean allowPathSearch,
            io.github.sandboxdemo.api.OutputLimitAction onOutputLimit,
            int maxCombinedOutputBytes,
            io.github.sandboxdemo.api.OutputCallbackMode outputCallbackMode,
            io.github.sandboxdemo.api.SandboxResourceLimits resourceLimits,
            io.github.sandboxdemo.api.SandboxIsolationLevel requiredIsolation) {
        this.resourceLimits = java.util.Objects.requireNonNull(resourceLimits, "resourceLimits");
        this.requiredIsolation =
                java.util.Objects.requireNonNull(requiredIsolation, "requiredIsolation");
        if (maxOutputBytes < 1
                || maxOutputBytes > 64 * 1024 * 1024
                || maxCombinedOutputBytes < 0
                || maxCombinedOutputBytes > 128 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid output budgets");
        }
        this.onOutputLimit = java.util.Objects.requireNonNull(onOutputLimit, "onOutputLimit");
        this.maxCombinedOutputBytes = maxCombinedOutputBytes;
        this.outputCallbackMode =
                java.util.Objects.requireNonNull(outputCallbackMode, "outputCallbackMode");
        this.workingDirectory = workingDirectory;
        this.readableRoots = readableRoots;
        this.writableRoots = writableRoots;
        this.protectedPaths = protectedPaths;
        this.privateTempDirectory = privateTempDirectory;
        this.networkPolicy = networkPolicy;
        this.readPolicy = readPolicy;
        this.deletionPolicy = deletionPolicy;
        this.timeout = timeout;
        this.maxOutputBytes = maxOutputBytes;
        this.allowPathSearch = allowPathSearch;
    }

    /**
     * Same effective filesystem/network policy, fixed capture limits and no caller output controls.
     */
    public ValidatedPolicy forProbe() {
        return new ValidatedPolicy(
                workingDirectory,
                readableRoots,
                writableRoots,
                protectedPaths,
                privateTempDirectory,
                networkPolicy,
                readPolicy,
                deletionPolicy,
                timeout,
                8192,
                false,
                io.github.sandboxdemo.api.OutputLimitAction.TRUNCATE,
                0,
                io.github.sandboxdemo.api.OutputCallbackMode.RAW,
                resourceLimits,
                requiredIsolation);
    }

    public io.github.sandboxdemo.api.SandboxResourceLimits resourceLimits() {
        return resourceLimits;
    }

    public io.github.sandboxdemo.api.SandboxIsolationLevel requiredIsolation() {
        return requiredIsolation;
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

    public DeletionPolicy deletionPolicy() {
        return deletionPolicy;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    public io.github.sandboxdemo.api.OutputLimitAction onOutputLimit() {
        return onOutputLimit;
    }

    public int maxCombinedOutputBytes() {
        return maxCombinedOutputBytes;
    }

    public io.github.sandboxdemo.api.OutputCallbackMode outputCallbackMode() {
        return outputCallbackMode;
    }

    public boolean allowPathSearch() {
        return allowPathSearch;
    }
}
