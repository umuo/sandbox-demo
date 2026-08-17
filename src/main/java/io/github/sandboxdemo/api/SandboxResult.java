package io.github.sandboxdemo.api;

import io.github.sandboxdemo.core.OutputCharsetDetector;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/** Captured result of one sandboxed process tree. */
public final class SandboxResult {

    private final int exitCode;
    private final boolean timedOut;
    private final byte[] stdout;
    private final byte[] stderr;
    private final boolean stdoutTruncated;
    private final boolean stderrTruncated;
    private final Duration duration;

    public SandboxResult(
            int exitCode,
            boolean timedOut,
            byte[] stdout,
            byte[] stderr,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Duration duration) {
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.stdout = Arrays.copyOf(stdout, stdout.length);
        this.stderr = Arrays.copyOf(stderr, stderr.length);
        this.stdoutTruncated = stdoutTruncated;
        this.stderrTruncated = stderrTruncated;
        this.duration = duration;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public byte[] stdout() {
        return Arrays.copyOf(stdout, stdout.length);
    }

    public byte[] stderr() {
        return Arrays.copyOf(stderr, stderr.length);
    }

    public boolean stdoutTruncated() {
        return stdoutTruncated;
    }

    public boolean stderrTruncated() {
        return stderrTruncated;
    }

    public Duration duration() {
        return duration;
    }

    public String stdoutUtf8() {
        return new String(stdout, StandardCharsets.UTF_8);
    }

    public String stderrUtf8() {
        return new String(stderr, StandardCharsets.UTF_8);
    }

    /** Decodes captured stdout using an explicitly selected charset. */
    public String stdoutText(Charset charset) {
        return new String(stdout, Objects.requireNonNull(charset, "charset"));
    }

    /** Decodes captured stderr using an explicitly selected charset. */
    public String stderrText(Charset charset) {
        return new String(stderr, Objects.requireNonNull(charset, "charset"));
    }

    /** Detects BOM/UTF-8 and otherwise falls back to the platform output charset. */
    public String stdoutTextAuto() {
        return OutputCharsetDetector.decodeAuto(stdout);
    }

    /** Detects BOM/UTF-8 and otherwise falls back to the platform output charset. */
    public String stderrTextAuto() {
        return OutputCharsetDetector.decodeAuto(stderr);
    }

    /** Auto-detects stdout with an explicit fallback for ambiguous legacy bytes. */
    public String stdoutTextAuto(Charset fallback) {
        return OutputCharsetDetector.decodeAuto(stdout, fallback);
    }

    /** Auto-detects stderr with an explicit fallback for ambiguous legacy bytes. */
    public String stderrTextAuto(Charset fallback) {
        return OutputCharsetDetector.decodeAuto(stderr, fallback);
    }

    public boolean successful() {
        return !timedOut && exitCode == 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SandboxResult)) {
            return false;
        }
        SandboxResult that = (SandboxResult) other;
        return exitCode == that.exitCode
                && timedOut == that.timedOut
                && stdoutTruncated == that.stdoutTruncated
                && stderrTruncated == that.stderrTruncated
                && Arrays.equals(stdout, that.stdout)
                && Arrays.equals(stderr, that.stderr)
                && Objects.equals(duration, that.duration);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(exitCode, timedOut, stdoutTruncated, stderrTruncated, duration);
        result = 31 * result + Arrays.hashCode(stdout);
        return 31 * result + Arrays.hashCode(stderr);
    }

    @Override
    public String toString() {
        return "SandboxResult[exitCode="
                + exitCode
                + ", timedOut="
                + timedOut
                + ", stdout="
                + Arrays.toString(stdout)
                + ", stderr="
                + Arrays.toString(stderr)
                + ", stdoutTruncated="
                + stdoutTruncated
                + ", stderrTruncated="
                + stderrTruncated
                + ", duration="
                + duration
                + "]";
    }
}
