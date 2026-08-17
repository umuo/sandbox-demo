package io.github.sandboxdemo.core;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/** Internal output-encoding detection shared by captured and streaming text. */
public final class OutputCharsetDetector {

    static final int UTF8_INVALID = -1;
    static final int UTF8_INCOMPLETE = 0;
    static final int UTF8_VALID = 1;

    private OutputCharsetDetector() {}

    public static String decodeAuto(byte[] bytes) {
        return decodeAuto(bytes, platformFallbackCharset());
    }

    public static String decodeAuto(byte[] bytes, Charset fallback) {
        Objects.requireNonNull(bytes, "bytes");
        Charset detected = detect(bytes, Objects.requireNonNull(fallback, "fallback"));
        int offset = bomLength(bytes, detected);
        return new String(bytes, offset, bytes.length - offset, detected);
    }

    public static Charset detect(byte[] bytes, Charset fallback) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(fallback, "fallback");
        Charset bom = bomCharset(bytes);
        if (bom != null) {
            return bom;
        }
        Charset utf16 = detectBomlessUtf16(bytes);
        if (utf16 != null) {
            return utf16;
        }
        return utf8Status(bytes, true) == UTF8_VALID ? StandardCharsets.UTF_8 : fallback;
    }

    public static Charset platformFallbackCharset() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return Charset.defaultCharset();
        }
        try {
            int codePage = WindowsCodePages.INSTANCE.GetOEMCP();
            if (codePage == 65001) {
                return StandardCharsets.UTF_8;
            }
            if (codePage > 0) {
                return windowsCodePageCharset(codePage);
            }
        } catch (Throwable ignored) {
            // A custom runtime can omit JNA natives or a specific Windows code-page alias.
        }
        return Charset.defaultCharset();
    }

    private static Charset windowsCodePageCharset(int codePage) {
        String preferred;
        switch (codePage) {
            case 936:
                preferred = "GBK";
                break;
            case 950:
                preferred = "Big5";
                break;
            case 932:
                preferred = "windows-31j";
                break;
            case 949:
                preferred = "x-windows-949";
                break;
            default:
                preferred = "cp" + codePage;
                break;
        }
        return Charset.forName(preferred);
    }

    static Charset bomCharset(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return StandardCharsets.UTF_8;
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return StandardCharsets.UTF_16LE;
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    static Charset detectBomlessUtf16(byte[] bytes) {
        int length = Math.min(bytes.length, 64);
        if (length < 4) {
            return null;
        }
        int pairs = length / 2;
        int evenZeros = 0;
        int oddZeros = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            if (bytes[i] == 0) {
                evenZeros++;
            }
            if (bytes[i + 1] == 0) {
                oddZeros++;
            }
        }
        int threshold = Math.max(2, (pairs * 3) / 4);
        if (oddZeros >= threshold && evenZeros == 0) {
            return StandardCharsets.UTF_16LE;
        }
        if (evenZeros >= threshold && oddZeros == 0) {
            return StandardCharsets.UTF_16BE;
        }
        return null;
    }

    static int utf8Status(byte[] bytes, boolean endOfInput) {
        java.nio.charset.CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes);
        CharBuffer output = CharBuffer.allocate(Math.max(1, bytes.length));
        while (true) {
            CoderResult result = decoder.decode(input, output, endOfInput);
            if (result.isError()) {
                return UTF8_INVALID;
            }
            if (result.isOverflow()) {
                output.clear();
                continue;
            }
            return input.hasRemaining() ? UTF8_INCOMPLETE : UTF8_VALID;
        }
    }

    static int bomLength(byte[] bytes, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8) && startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return 3;
        }
        if ((charset.equals(StandardCharsets.UTF_16LE) && startsWith(bytes, 0xFF, 0xFE))
                || (charset.equals(StandardCharsets.UTF_16BE) && startsWith(bytes, 0xFE, 0xFF))) {
            return 2;
        }
        return 0;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private interface WindowsCodePages extends StdCallLibrary {
        WindowsCodePages INSTANCE =
                Native.load("kernel32", WindowsCodePages.class, W32APIOptions.UNICODE_OPTIONS);

        int GetOEMCP();
    }
}
