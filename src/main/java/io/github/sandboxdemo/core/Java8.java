package io.github.sandboxdemo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Small compatibility helpers for collection and string APIs added after Java 8. */
public final class Java8 {

    private Java8() {}

    @SafeVarargs
    public static <T> List<T> listOf(T... elements) {
        Objects.requireNonNull(elements, "elements");
        List<T> result = new ArrayList<>(Arrays.asList(elements));
        if (result.contains(null)) {
            throw new NullPointerException("elements must not contain null");
        }
        return Collections.unmodifiableList(result);
    }

    @SafeVarargs
    public static <T> Set<T> setOf(T... elements) {
        Objects.requireNonNull(elements, "elements");
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T element : elements) {
            if (!result.add(Objects.requireNonNull(element, "element"))) {
                throw new IllegalArgumentException("duplicate element: " + element);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static <K, V> Map<K, V> mapOf() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> mapOf(K key, V value) {
        return Collections.singletonMap(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    public static <T> List<T> copyList(Iterable<? extends T> source) {
        Objects.requireNonNull(source, "source");
        List<T> result = new ArrayList<>();
        for (T element : source) {
            result.add(Objects.requireNonNull(element, "element"));
        }
        return Collections.unmodifiableList(result);
    }

    public static <T> Set<T> copySet(Iterable<? extends T> source) {
        Objects.requireNonNull(source, "source");
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T element : source) {
            result.add(Objects.requireNonNull(element, "element"));
        }
        return Collections.unmodifiableSet(result);
    }

    public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> source) {
        Objects.requireNonNull(source, "source");
        LinkedHashMap<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : source.entrySet()) {
            result.put(
                    Objects.requireNonNull(entry.getKey(), "key"),
                    Objects.requireNonNull(entry.getValue(), "value"));
        }
        return Collections.unmodifiableMap(result);
    }

    public static boolean isBlank(String value) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String repeat(String value, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count is negative: " + count);
        }
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    public static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    public static String toHex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }

    public static void writeString(Path path, String value) throws IOException {
        writeString(path, value, StandardCharsets.UTF_8);
    }

    public static void writeString(Path path, String value, Charset charset) throws IOException {
        Files.write(
                path,
                value.getBytes(charset),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    public static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
