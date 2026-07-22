package io.github.sandboxdemo.platform.windows;

import java.util.List;

/** Implements the CommandLineToArgvW-compatible Windows quoting algorithm. */
final class WindowsCommandLine {

    private WindowsCommandLine() {}

    static String build(String executable, List<String> arguments) {
        StringBuilder result = new StringBuilder(quote(executable));
        for (String argument : arguments) {
            result.append(' ').append(quote(argument));
        }
        return result.toString();
    }

    static String quote(String value) {
        if (!value.isEmpty()
                && value.chars().noneMatch(ch -> Character.isWhitespace(ch) || ch == '"')) {
            return value;
        }

        StringBuilder result = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\\') {
                backslashes++;
            } else if (current == '"') {
                result.append(io.github.sandboxdemo.core.Java8.repeat("\\", backslashes * 2 + 1))
                        .append('"');
                backslashes = 0;
            } else {
                result.append(io.github.sandboxdemo.core.Java8.repeat("\\", backslashes))
                        .append(current);
                backslashes = 0;
            }
        }
        result.append(io.github.sandboxdemo.core.Java8.repeat("\\", backslashes * 2)).append('"');
        return result.toString();
    }
}
