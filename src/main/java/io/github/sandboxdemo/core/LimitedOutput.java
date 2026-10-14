package io.github.sandboxdemo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

public final class LimitedOutput {

    private final int limit;
    private final ByteArrayOutputStream bytes;
    private final OutputCallbacks callbacks;
    private boolean truncated;
    private final OutputBudget budget;
    private final io.github.sandboxdemo.api.OutputCallbackMode mode;

    LimitedOutput(int limit) {
        this(limit, null);
    }

    LimitedOutput(int limit, Consumer<byte[]> consumer) {
        this(limit, consumer, null);
    }

    LimitedOutput(int limit, Consumer<byte[]> byteConsumer, Consumer<String> textConsumer) {
        this(limit, byteConsumer, textConsumer, StandardCharsets.UTF_8, false);
    }

    LimitedOutput(
            int limit,
            Consumer<byte[]> byteConsumer,
            Consumer<String> textConsumer,
            Charset charset,
            boolean autoDetect) {
        this(
                limit,
                byteConsumer,
                textConsumer,
                charset,
                autoDetect,
                new OutputBudget(0, io.github.sandboxdemo.api.OutputLimitAction.TRUNCATE, null),
                io.github.sandboxdemo.api.OutputCallbackMode.RAW);
    }

    public LimitedOutput(
            int limit,
            Consumer<byte[]> byteConsumer,
            Consumer<String> textConsumer,
            Charset charset,
            boolean autoDetect,
            OutputBudget budget,
            io.github.sandboxdemo.api.OutputCallbackMode mode) {
        if (limit < 1 || limit > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid output limit");
        }
        this.limit = limit;
        this.budget = java.util.Objects.requireNonNull(budget, "budget");
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        this.callbacks = new OutputCallbacks(byteConsumer, textConsumer, charset, autoDetect);
    }

    void readFrom(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        try {
            while ((count = input.read(buffer)) >= 0) {
                accept(buffer, count);
                if (budget.shouldStopReading()) {
                    break;
                }
            }
        } finally {
            callbacks.complete();
        }
    }

    /** Account and request termination before invoking arbitrary observation callbacks. */
    public void accept(byte[] buffer, int count) {
        if (count < 0 || count > buffer.length) {
            throw new IllegalArgumentException("invalid output chunk length");
        }
        int allowed;
        synchronized (this) {
            allowed = budget.retain(limit - bytes.size(), count);
            bytes.write(buffer, 0, allowed);
            if (allowed < count) {
                truncated = true;
            }
        }
        int observed = mode == io.github.sandboxdemo.api.OutputCallbackMode.RAW ? count : allowed;
        if (observed > 0) {
            callbacks.accept(Arrays.copyOf(buffer, observed));
        }
    }

    public void complete() {
        callbacks.complete();
    }

    public boolean shouldStopReading() {
        return budget.shouldStopReading();
    }

    public synchronized byte[] bytes() {
        return bytes.toByteArray();
    }

    public synchronized boolean truncated() {
        return truncated;
    }
}
