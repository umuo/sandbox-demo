package io.github.sandboxdemo.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An executable and its argument vector. No shell is inserted implicitly. */
public final class CommandSpec {

    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_COMMAND_CHARACTERS = 30_000;

    private final String executable;
    private final List<String> arguments;

    public CommandSpec(String executable, List<String> arguments) {
        if (executable == null || io.github.sandboxdemo.core.Java8.isBlank(executable)) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        if (executable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("executable must not contain NUL");
        }
        List<String> copiedArguments =
                Collections.unmodifiableList(
                        new ArrayList<>(Objects.requireNonNull(arguments, "arguments")));
        if (copiedArguments.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("arguments must not contain null");
        }
        if (copiedArguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException(
                    "too many command arguments: " + copiedArguments.size());
        }
        long characters = executable.length();
        for (String argument : copiedArguments) {
            if (argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("arguments must not contain NUL");
            }
            characters += argument.length() + 1L;
        }
        if (characters > MAX_COMMAND_CHARACTERS) {
            throw new IllegalArgumentException(
                    "command exceeds the cross-platform character limit: " + characters);
        }
        this.executable = executable;
        this.arguments = copiedArguments;
    }

    public String executable() {
        return executable;
    }

    public List<String> arguments() {
        return arguments;
    }

    public static CommandSpec of(String executable, String... arguments) {
        return new CommandSpec(executable, java.util.Arrays.asList(arguments));
    }

    public List<String> asArgumentVector() {
        List<String> result = new ArrayList<>(arguments.size() + 1);
        result.add(executable);
        result.addAll(arguments);
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CommandSpec)) {
            return false;
        }
        CommandSpec that = (CommandSpec) other;
        return executable.equals(that.executable) && arguments.equals(that.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(executable, arguments);
    }

    @Override
    public String toString() {
        return "CommandSpec[executable=" + executable + ", arguments=" + arguments + "]";
    }
}
