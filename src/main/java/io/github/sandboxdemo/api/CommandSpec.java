package io.github.sandboxdemo.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An executable and its argument vector. No shell is inserted implicitly. */
public record CommandSpec(String executable, List<String> arguments) {

    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_COMMAND_CHARACTERS = 30_000;

    public CommandSpec {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        if (executable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("executable must not contain NUL");
        }
        arguments =
                Collections.unmodifiableList(
                        new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
        if (arguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("arguments must not contain null");
        }
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("too many command arguments: " + arguments.size());
        }
        long characters = executable.length();
        for (String argument : arguments) {
            if (argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("arguments must not contain NUL");
            }
            characters += argument.length() + 1L;
        }
        if (characters > MAX_COMMAND_CHARACTERS) {
            throw new IllegalArgumentException(
                    "command exceeds the cross-platform character limit: " + characters);
        }
    }

    public static CommandSpec of(String executable, String... arguments) {
        return new CommandSpec(executable, List.of(arguments));
    }

    public List<String> asArgumentVector() {
        List<String> result = new ArrayList<>(arguments.size() + 1);
        result.add(executable);
        result.addAll(arguments);
        return List.copyOf(result);
    }
}
