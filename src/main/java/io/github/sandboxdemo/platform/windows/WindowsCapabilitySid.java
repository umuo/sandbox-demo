package io.github.sandboxdemo.platform.windows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;

/** Creates a one-execution synthetic SID for a writable-root capability. */
final class WindowsCapabilitySid {

    private static final SecureRandom RANDOM = new SecureRandom();

    private WindowsCapabilitySid() {}

    static String random() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        ByteBuffer values = ByteBuffer.wrap(random).order(ByteOrder.LITTLE_ENDIAN);
        return "S-1-5-21-"
                + Integer.toUnsignedString(nonZero(values.getInt()))
                + '-'
                + Integer.toUnsignedString(nonZero(values.getInt()))
                + '-'
                + Integer.toUnsignedString(nonZero(values.getInt()))
                + '-'
                + Integer.toUnsignedString(nonZero(values.getInt()));
    }

    private static int nonZero(int value) {
        return value == 0 ? 1 : value;
    }
}
