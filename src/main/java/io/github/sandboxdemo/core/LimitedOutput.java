package io.github.sandboxdemo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class LimitedOutput {

    private final int limit;
    private final ByteArrayOutputStream bytes;
    private boolean truncated;

    LimitedOutput(int limit) {
        this.limit = limit;
        this.bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
    }

    void readFrom(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            int remaining = limit - bytes.size();
            if (remaining > 0) {
                bytes.write(buffer, 0, Math.min(remaining, count));
            }
            if (count > remaining) {
                truncated = true;
            }
        }
    }

    byte[] bytes() {
        return bytes.toByteArray();
    }

    boolean truncated() {
        return truncated;
    }
}
