package io.github.sandboxdemo.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/** Captured result of one sandboxed process tree. */
public record SandboxResult(
        int exitCode,
        boolean timedOut,
        byte[] stdout,
        byte[] stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        Duration duration) {

    public SandboxResult {
        stdout = Arrays.copyOf(stdout, stdout.length);
        stderr = Arrays.copyOf(stderr, stderr.length);
    }

    @Override
    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    @Override
    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }

    public String stdoutUtf8() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderrUtf8() {
        return new String(stderr, StandardCharsets.UTF_8);
    }

    public boolean successful() {
        return !timedOut && exitCode == 0;
    }
}
