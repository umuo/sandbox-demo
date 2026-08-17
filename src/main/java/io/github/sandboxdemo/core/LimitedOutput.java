package io.github.sandboxdemo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

final class LimitedOutput {

    private final int limit;
    private final ByteArrayOutputStream bytes;
    private final OutputCallbacks callbacks;
    private boolean truncated;

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
        this.limit = limit;
        this.bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        this.callbacks = new OutputCallbacks(byteConsumer, textConsumer, charset, autoDetect);
    }

    void readFrom(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        try {
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    callbacks.accept(Arrays.copyOf(buffer, count));
                }
                int remaining = limit - bytes.size();
                if (remaining > 0) {
                    bytes.write(buffer, 0, Math.min(remaining, count));
                }
                if (count > remaining) {
                    truncated = true;
                }
            }
        } finally {
            callbacks.complete();
        }
    }

    byte[] bytes() {
        return bytes.toByteArray();
    }

    boolean truncated() {
        return truncated;
    }
}
