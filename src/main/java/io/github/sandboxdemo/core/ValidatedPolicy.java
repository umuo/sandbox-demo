package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public record ValidatedPolicy(
        Path workingDirectory,
        List<Path> readableRoots,
        List<Path> writableRoots,
        List<Path> protectedPaths,
        Path privateTempDirectory,
        NetworkPolicy networkPolicy,
        ReadPolicy readPolicy,
        Duration timeout,
        int maxOutputBytes,
        boolean allowPathSearch) {}
