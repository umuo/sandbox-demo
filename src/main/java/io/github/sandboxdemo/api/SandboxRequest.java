package io.github.sandboxdemo.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** One command execution request and its explicitly supplied environment. */
public final class SandboxRequest {

    private static final int MAX_ENVIRONMENT_ENTRIES = 512;
    private static final int MAX_ENVIRONMENT_CHARACTERS = 30_000;
    private static final int MAX_STANDARD_INPUT_BYTES = 8 * 1024 * 1024;
    private static final Set<String> RESERVED_VARIABLES =
            Collections.unmodifiableSet(
                    new java.util.HashSet<>(
                            Arrays.asList(
                                    "TEMP",
                                    "TMP",
                                    "TMPDIR",
                                    "HOME",
                                    "USERPROFILE",
                                    "XDG_CACHE_HOME")));

    private final SandboxPolicy policy;
    private final CommandSpec command;
    private final Map<String, String> environment;
    private final byte[] standardInput;

    public SandboxRequest(
            SandboxPolicy policy,
            CommandSpec command,
            Map<String, String> environment,
            byte[] standardInput) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.command = Objects.requireNonNull(command, "command");
        Map<String, String> copiedEnvironment =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(environment, "environment")));
        byte[] copiedStandardInput =
                Arrays.copyOf(
                        Objects.requireNonNull(standardInput, "standardInput"),
                        standardInput.length);
        copiedEnvironment.forEach(SandboxRequest::validateEnvironmentEntry);
        validateEnvironment(copiedEnvironment);
        if (copiedStandardInput.length > MAX_STANDARD_INPUT_BYTES) {
            throw new IllegalArgumentException(
                    "standardInput exceeds limit " + MAX_STANDARD_INPUT_BYTES);
        }
        this.environment = copiedEnvironment;
        this.standardInput = copiedStandardInput;
    }

    public SandboxRequest(
            SandboxPolicy policy, CommandSpec command, Map<String, String> environment) {
        this(policy, command, environment, new byte[0]);
    }

    public static SandboxRequest of(SandboxPolicy policy, CommandSpec command) {
        return new SandboxRequest(policy, command, Collections.emptyMap(), new byte[0]);
    }

    public SandboxPolicy policy() {
        return policy;
    }

    public CommandSpec command() {
        return command;
    }

    public Map<String, String> environment() {
        return environment;
    }

    public byte[] standardInput() {
        return Arrays.copyOf(standardInput, standardInput.length);
    }

    /** Starts an Agent-friendly builder without inserting a shell implicitly. */
    public static Builder builder(Path workingDirectory, String executable) {
        return new Builder(workingDirectory, new CommandSpec(executable, Collections.emptyList()));
    }

    /** Starts an Agent-friendly builder from an existing command specification. */
    public static Builder builder(Path workingDirectory, CommandSpec command) {
        return new Builder(workingDirectory, command);
    }

    private static void validateEnvironmentEntry(String name, String value) {
        if (name == null
                || io.github.sandboxdemo.core.Java8.isBlank(name)
                || name.indexOf('=') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid environment variable name: " + name);
        }
        if (value == null || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid environment variable value for: " + name);
        }
        if (RESERVED_VARIABLES.contains(name.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "environment variable is controlled by the sandbox: " + name);
        }
    }

    private static void validateEnvironment(Map<String, String> environment) {
        if (environment.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IllegalArgumentException("too many environment variables");
        }
        long characters = 0;
        Set<String> caseInsensitiveNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (!caseInsensitiveNames.add(entry.getKey())) {
                throw new IllegalArgumentException(
                        "duplicate case-insensitive environment variable: " + entry.getKey());
            }
            characters += entry.getKey().length() + entry.getValue().length() + 2L;
        }
        if (characters > MAX_ENVIRONMENT_CHARACTERS) {
            throw new IllegalArgumentException(
                    "environment exceeds the cross-platform character limit: " + characters);
        }
    }

    /** Fluent builder that combines command, policy and explicit environment configuration. */
    public static final class Builder {

        private final SandboxPolicy.Builder policy;
        private final String executable;
        private final List<String> arguments;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private byte[] standardInput = new byte[0];

        private Builder(Path workingDirectory, CommandSpec command) {
            this.policy = SandboxPolicy.builder(workingDirectory);
            this.executable = command.executable();
            this.arguments = new ArrayList<>(command.arguments());
        }

        public Builder argument(String argument) {
            arguments.add(Objects.requireNonNull(argument, "argument"));
            return this;
        }

        public Builder arguments(String... arguments) {
            return arguments(Arrays.asList(arguments));
        }

        public Builder arguments(List<String> arguments) {
            this.arguments.addAll(Objects.requireNonNull(arguments, "arguments"));
            return this;
        }

        public Builder environment(String name, String value) {
            environment.put(
                    Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment.putAll(Objects.requireNonNull(environment, "environment"));
            return this;
        }

        /** Supplies bounded, one-shot standard input and closes stdin after writing it. */
        public Builder standardInput(byte[] standardInput) {
            this.standardInput =
                    java.util.Arrays.copyOf(
                            Objects.requireNonNull(standardInput, "standardInput"),
                            standardInput.length);
            return this;
        }

        /** UTF-8 convenience form of {@link #standardInput(byte[])}. */
        public Builder standardInputUtf8(String standardInput) {
            return standardInput(
                    Objects.requireNonNull(standardInput, "standardInput")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public Builder readableRoot(Path root) {
            policy.readableRoot(root);
            return this;
        }

        public Builder writableRoot(Path root) {
            policy.writableRoot(root);
            return this;
        }

        /** Removes the implicit write grant for the working directory. */
        public Builder readOnlyWorkingDirectory() {
            policy.readOnlyWorkingDirectory();
            return this;
        }

        public Builder protect(Path path) {
            policy.protect(path);
            return this;
        }

        public Builder network(NetworkPolicy networkPolicy) {
            policy.network(networkPolicy);
            return this;
        }

        public Builder readPolicy(ReadPolicy readPolicy) {
            policy.readPolicy(readPolicy);
            return this;
        }

        public Builder timeout(Duration timeout) {
            policy.timeout(timeout);
            return this;
        }

        public Builder maxOutputBytes(int maxOutputBytes) {
            policy.maxOutputBytes(maxOutputBytes);
            return this;
        }

        public Builder allowPathSearch(boolean allowPathSearch) {
            policy.allowPathSearch(allowPathSearch);
            return this;
        }

        public SandboxRequest build() {
            return new SandboxRequest(
                    policy.build(),
                    new CommandSpec(executable, arguments),
                    environment,
                    standardInput);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SandboxRequest)) {
            return false;
        }
        SandboxRequest that = (SandboxRequest) other;
        return policy.equals(that.policy)
                && command.equals(that.command)
                && environment.equals(that.environment)
                && Arrays.equals(standardInput, that.standardInput);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(policy, command, environment);
        return 31 * result + Arrays.hashCode(standardInput);
    }

    @Override
    public String toString() {
        return "SandboxRequest[policy="
                + policy
                + ", command="
                + command
                + ", environment="
                + environment
                + ", standardInput="
                + Arrays.toString(standardInput)
                + "]";
    }
}
