package io.github.sandboxdemo.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/** Per-execution output callback dispatcher with stateful charset decoding. */
public final class OutputCallbacks {

    private final Consumer<byte[]> byteConsumer;
    private final TextDecoder textDecoder;

    public OutputCallbacks(Consumer<byte[]> byteConsumer, Consumer<String> textConsumer) {
        this(byteConsumer, textConsumer, StandardCharsets.UTF_8, false);
    }

    public OutputCallbacks(
            Consumer<byte[]> byteConsumer,
            Consumer<String> textConsumer,
            Charset charset,
            boolean autoDetect) {
        this.byteConsumer = byteConsumer;
        this.textDecoder =
                textConsumer == null
                        ? null
                        : autoDetect
                                ? new AutoDetectingDecoder(textConsumer, charset)
                                : new StreamingDecoder(textConsumer, charset);
    }

    public void accept(byte[] chunk) {
        if (chunk.length == 0) {
            return;
        }
        if (byteConsumer != null) {
            acceptIgnoringFailure(byteConsumer, Arrays.copyOf(chunk, chunk.length));
        }
        if (textDecoder != null) {
            textDecoder.accept(chunk);
        }
    }

    public void complete() {
        if (textDecoder != null) {
            textDecoder.complete();
        }
    }

    private static <T> void acceptIgnoringFailure(Consumer<T> consumer, T value) {
        try {
            consumer.accept(value);
        } catch (Throwable ignored) {
            // User callbacks cannot interrupt output capture or process supervision.
        }
    }

    private interface TextDecoder {

        void accept(byte[] chunk);

        void complete();
    }

    private static final class StreamingDecoder implements TextDecoder {

        private final java.nio.charset.CharsetDecoder decoder;
        private final Consumer<String> consumer;
        private byte[] remainder = new byte[0];
        private boolean completed;

        private StreamingDecoder(Consumer<String> consumer, Charset charset) {
            this.consumer = consumer;
            this.decoder =
                    charset.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }

        @Override
        public synchronized void accept(byte[] chunk) {
            if (completed) {
                return;
            }
            byte[] inputBytes = new byte[remainder.length + chunk.length];
            System.arraycopy(remainder, 0, inputBytes, 0, remainder.length);
            System.arraycopy(chunk, 0, inputBytes, remainder.length, chunk.length);
            ByteBuffer input = ByteBuffer.wrap(inputBytes);
            decode(input, false);
            remainder = new byte[input.remaining()];
            input.get(remainder);
        }

        @Override
        public synchronized void complete() {
            if (completed) {
                return;
            }
            completed = true;
            ByteBuffer input = ByteBuffer.wrap(remainder);
            decode(input, true);
            flush();
            remainder = new byte[0];
        }

        private void decode(ByteBuffer input, boolean endOfInput) {
            CharBuffer characters = CharBuffer.allocate(Math.max(32, input.remaining() + 1));
            while (true) {
                java.nio.charset.CoderResult result = decoder.decode(input, characters, endOfInput);
                emit(characters);
                if (!result.isOverflow()) {
                    return;
                }
            }
        }

        private void flush() {
            CharBuffer characters = CharBuffer.allocate(32);
            while (true) {
                java.nio.charset.CoderResult result = decoder.flush(characters);
                emit(characters);
                if (!result.isOverflow()) {
                    return;
                }
            }
        }

        private void emit(CharBuffer characters) {
            characters.flip();
            if (characters.hasRemaining()) {
                acceptIgnoringFailure(consumer, characters.toString());
            }
            characters.clear();
        }
    }

    private static final class AutoDetectingDecoder implements TextDecoder {

        private final Consumer<String> consumer;
        private final Charset fallback;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private StreamingDecoder selected;
        private boolean completed;
        private boolean atStart = true;

        private AutoDetectingDecoder(Consumer<String> consumer, Charset fallback) {
            this.consumer = consumer;
            this.fallback =
                    fallback == null ? OutputCharsetDetector.platformFallbackCharset() : fallback;
        }

        @Override
        public synchronized void accept(byte[] chunk) {
            if (completed) {
                return;
            }
            if (selected != null) {
                selected.accept(chunk);
                return;
            }
            pending.write(chunk, 0, chunk.length);
            trySelect();
        }

        @Override
        public synchronized void complete() {
            if (completed) {
                return;
            }
            completed = true;
            if (selected == null) {
                byte[] bytes = pending.toByteArray();
                Charset charset;
                if (atStart) {
                    charset = OutputCharsetDetector.detect(bytes, fallback);
                } else {
                    charset =
                            OutputCharsetDetector.utf8Status(bytes, true)
                                            == OutputCharsetDetector.UTF8_VALID
                                    ? StandardCharsets.UTF_8
                                    : fallback;
                }
                int offset = atStart ? OutputCharsetDetector.bomLength(bytes, charset) : 0;
                select(charset, bytes, offset);
            }
            selected.complete();
        }

        private void trySelect() {
            byte[] bytes = pending.toByteArray();
            if (bytes.length < 4) {
                return;
            }
            if (atStart) {
                Charset bom = OutputCharsetDetector.bomCharset(bytes);
                if (bom != null) {
                    select(bom, bytes, OutputCharsetDetector.bomLength(bytes, bom));
                    return;
                }
                Charset utf16 = OutputCharsetDetector.detectBomlessUtf16(bytes);
                if (utf16 != null) {
                    select(utf16, bytes, 0);
                    return;
                }
            }

            int nonAscii = firstNonAscii(bytes);
            if (nonAscii < 0) {
                acceptIgnoringFailure(consumer, new String(bytes, StandardCharsets.US_ASCII));
                pending.reset();
                atStart = false;
                return;
            }
            if (nonAscii > 0) {
                acceptIgnoringFailure(
                        consumer, new String(bytes, 0, nonAscii, StandardCharsets.US_ASCII));
                bytes = Arrays.copyOfRange(bytes, nonAscii, bytes.length);
                pending.reset();
                pending.write(bytes, 0, bytes.length);
                atStart = false;
            }

            int utf8 = OutputCharsetDetector.utf8Status(bytes, false);
            if (utf8 == OutputCharsetDetector.UTF8_VALID) {
                select(StandardCharsets.UTF_8, bytes, 0);
            } else if (utf8 == OutputCharsetDetector.UTF8_INVALID) {
                select(fallback, bytes, 0);
            }
        }

        private void select(Charset charset, byte[] bytes, int offset) {
            selected = new StreamingDecoder(consumer, charset);
            pending.reset();
            atStart = false;
            if (offset < bytes.length) {
                selected.accept(Arrays.copyOfRange(bytes, offset, bytes.length));
            }
        }

        private static int firstNonAscii(byte[] bytes) {
            for (int i = 0; i < bytes.length; i++) {
                if ((bytes[i] & 0x80) != 0 || bytes[i] == 0) {
                    return i;
                }
            }
            return -1;
        }
    }
}
