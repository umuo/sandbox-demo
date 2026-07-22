package io.github.sandboxdemo.platform.windows;

import java.util.List;

/** Implements the CommandLineToArgvW-compatible Windows quoting algorithm. */
final class WindowsCommandLine {

    private WindowsCommandLine() {}

    static String build(String executable, List<String> arguments) {
        StringBuilder result = new StringBuilder(quote(executable));
        for (int i = 0; i < arguments.size(); i++) {
            String argument = arguments.get(i);
            result.append(' ');
            if (isCmdCommand(executable, arguments, i)) {
                // cmd.exe parses the text after /c or /k itself rather than with
                // CommandLineToArgvW. Backslash-escaping embedded quotes changes shell syntax
                // (for example, a quoted redirection path) because backslash is not cmd's quote
                // escape character. /s removes this outer pair and leaves the command intact.
                result.append('"').append(argument).append('"');
            } else {
                result.append(quote(argument));
            }
        }
        return result.toString();
    }

    private static boolean isCmdCommand(
            String executable, List<String> arguments, int argumentIndex) {
        if (argumentIndex == 0 || argumentIndex != arguments.size() - 1) {
            return false;
        }
        int separator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        String name = executable.substring(separator + 1);
        if (!(name.equalsIgnoreCase("cmd.exe") || name.equalsIgnoreCase("cmd"))) {
            return false;
        }
        String previous = arguments.get(argumentIndex - 1);
        return previous.equalsIgnoreCase("/c") || previous.equalsIgnoreCase("/k");
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
