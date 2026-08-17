package io.github.sandboxdemo.api;

import io.github.sandboxdemo.core.OutputCharsetDetector;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
import java.util.function.Consumer;

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
    private final Consumer<byte[]> stdoutConsumer;
    private final Consumer<byte[]> stderrConsumer;
    private final Consumer<String> stdoutTextConsumer;
    private final Consumer<String> stderrTextConsumer;
    private final Charset stdoutCharset;
    private final Charset stderrCharset;
    private final boolean stdoutCharsetAuto;
    private final boolean stderrCharsetAuto;

    public SandboxRequest(
            SandboxPolicy policy,
            CommandSpec command,
            Map<String, String> environment,
            byte[] standardInput,
            Consumer<byte[]> stdoutConsumer,
            Consumer<byte[]> stderrConsumer) {
        this(
                policy,
                command,
                environment,
                standardInput,
                stdoutConsumer,
                stderrConsumer,
                null,
                null,
                StandardCharsets.UTF_8,
                StandardCharsets.UTF_8,
                false,
                false);
    }

    private SandboxRequest(
            SandboxPolicy policy,
            CommandSpec command,
            Map<String, String> environment,
            byte[] standardInput,
            Consumer<byte[]> stdoutConsumer,
            Consumer<byte[]> stderrConsumer,
            Consumer<String> stdoutTextConsumer,
            Consumer<String> stderrTextConsumer,
            Charset stdoutCharset,
            Charset stderrCharset,
            boolean stdoutCharsetAuto,
            boolean stderrCharsetAuto) {
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
        this.stdoutConsumer = stdoutConsumer;
        this.stderrConsumer = stderrConsumer;
        this.stdoutTextConsumer = stdoutTextConsumer;
        this.stderrTextConsumer = stderrTextConsumer;
        this.stdoutCharset = Objects.requireNonNull(stdoutCharset, "stdoutCharset");
        this.stderrCharset = Objects.requireNonNull(stderrCharset, "stderrCharset");
        this.stdoutCharsetAuto = stdoutCharsetAuto;
        this.stderrCharsetAuto = stderrCharsetAuto;
    }

    public SandboxRequest(
            SandboxPolicy policy,
            CommandSpec command,
            Map<String, String> environment,
            byte[] standardInput) {
        this(policy, command, environment, standardInput, null, null);
    }

    public SandboxRequest(
            SandboxPolicy policy, CommandSpec command, Map<String, String> environment) {
        this(policy, command, environment, new byte[0], null, null);
    }

    public static SandboxRequest of(SandboxPolicy policy, CommandSpec command) {
        return new SandboxRequest(policy, command, Collections.emptyMap(), new byte[0], null, null);
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

    public Consumer<byte[]> stdoutConsumer() {
        return stdoutConsumer;
    }

    public Consumer<byte[]> stderrConsumer() {
        return stderrConsumer;
    }

    public Consumer<String> stdoutTextConsumer() {
        return stdoutTextConsumer;
    }

    public Consumer<String> stderrTextConsumer() {
        return stderrTextConsumer;
    }

    /** Charset used directly, or as the fallback when stdout auto-detection is enabled. */
    public Charset stdoutCharset() {
        return stdoutCharset;
    }

    /** Charset used directly, or as the fallback when stderr auto-detection is enabled. */
    public Charset stderrCharset() {
        return stderrCharset;
    }

    public boolean stdoutCharsetAuto() {
        return stdoutCharsetAuto;
    }

    public boolean stderrCharsetAuto() {
        return stderrCharsetAuto;
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
        private Consumer<byte[]> stdoutConsumer;
        private Consumer<byte[]> stderrConsumer;
        private Consumer<String> stdoutTextConsumer;
        private Consumer<String> stderrTextConsumer;
        private Charset stdoutCharset = StandardCharsets.UTF_8;
        private Charset stderrCharset = StandardCharsets.UTF_8;
        private boolean stdoutCharsetAuto;
        private boolean stderrCharsetAuto;

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
                            .getBytes(StandardCharsets.UTF_8));
        }

        /** Registers a real-time consumer for raw standard output bytes. */
        public Builder stdoutConsumer(Consumer<byte[]> stdoutConsumer) {
            if (stdoutConsumer == null) {
                return this;
            }
            this.stdoutConsumer = combineConsumers(this.stdoutConsumer, stdoutConsumer);
            return this;
        }

        /** Registers a real-time consumer for raw standard error bytes. */
        public Builder stderrConsumer(Consumer<byte[]> stderrConsumer) {
            if (stderrConsumer == null) {
                return this;
            }
            this.stderrConsumer = combineConsumers(this.stderrConsumer, stderrConsumer);
            return this;
        }

        /** Registers a real-time stdout text consumer (UTF-8 unless configured otherwise). */
        public Builder stdoutTextConsumer(Consumer<String> stdoutTextConsumer) {
            Objects.requireNonNull(stdoutTextConsumer, "stdoutTextConsumer");
            this.stdoutTextConsumer = combineConsumers(this.stdoutTextConsumer, stdoutTextConsumer);
            return this;
        }

        /** Registers a real-time stderr text consumer (UTF-8 unless configured otherwise). */
        public Builder stderrTextConsumer(Consumer<String> stderrTextConsumer) {
            Objects.requireNonNull(stderrTextConsumer, "stderrTextConsumer");
            this.stderrTextConsumer = combineConsumers(this.stderrTextConsumer, stderrTextConsumer);
            return this;
        }

        /** Uses one explicit charset for both stdout and stderr text callbacks. */
        public Builder outputCharset(Charset charset) {
            return stdoutCharset(charset).stderrCharset(charset);
        }

        /** Uses an explicit charset for stdout text callbacks. */
        public Builder stdoutCharset(Charset charset) {
            this.stdoutCharset = Objects.requireNonNull(charset, "charset");
            this.stdoutCharsetAuto = false;
            return this;
        }

        /** Uses an explicit charset for stderr text callbacks. */
        public Builder stderrCharset(Charset charset) {
            this.stderrCharset = Objects.requireNonNull(charset, "charset");
            this.stderrCharsetAuto = false;
            return this;
        }

        /** Auto-detects both text streams and falls back to the platform output charset. */
        public Builder outputCharsetAuto() {
            Charset fallback = OutputCharsetDetector.platformFallbackCharset();
            return outputCharsetAuto(fallback);
        }

        /** Auto-detects both text streams with an explicit legacy-encoding fallback. */
        public Builder outputCharsetAuto(Charset fallback) {
            stdoutCharsetAuto(fallback);
            stderrCharsetAuto(fallback);
            return this;
        }

        public Builder stdoutCharsetAuto() {
            return stdoutCharsetAuto(OutputCharsetDetector.platformFallbackCharset());
        }

        public Builder stdoutCharsetAuto(Charset fallback) {
            this.stdoutCharset = Objects.requireNonNull(fallback, "fallback");
            this.stdoutCharsetAuto = true;
            return this;
        }

        public Builder stderrCharsetAuto() {
            return stderrCharsetAuto(OutputCharsetDetector.platformFallbackCharset());
        }

        public Builder stderrCharsetAuto(Charset fallback) {
            this.stderrCharset = Objects.requireNonNull(fallback, "fallback");
            this.stderrCharsetAuto = true;
            return this;
        }

        /** Registers a structured {@link SandboxOutputListener} for stdout and stderr streaming. */
        public Builder outputListener(SandboxOutputListener listener) {
            Objects.requireNonNull(listener, "listener");
            stdoutConsumer(listener::onStdout);
            stdoutTextConsumer(listener::onStdoutText);
            stderrConsumer(listener::onStderr);
            stderrTextConsumer(listener::onStderrText);
            return this;
        }

        private static <T> Consumer<T> combineConsumers(
                Consumer<T> existing, Consumer<T> additional) {
            if (existing == null) {
                return additional;
            }
            return value -> {
                acceptIgnoringFailure(existing, value);
                acceptIgnoringFailure(additional, value);
            };
        }

        private static <T> void acceptIgnoringFailure(Consumer<T> consumer, T value) {
            try {
                consumer.accept(value);
            } catch (Throwable ignored) {
                // One callback must not suppress later callbacks or process supervision.
            }
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

        public Builder deletion(DeletionPolicy deletionPolicy) {
            policy.deletion(deletionPolicy);
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
                    standardInput,
                    stdoutConsumer,
                    stderrConsumer,
                    stdoutTextConsumer,
                    stderrTextConsumer,
                    stdoutCharset,
                    stderrCharset,
                    stdoutCharsetAuto,
                    stderrCharsetAuto);
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
                && Arrays.equals(standardInput, that.standardInput)
                && stdoutCharset.equals(that.stdoutCharset)
                && stderrCharset.equals(that.stderrCharset)
                && stdoutCharsetAuto == that.stdoutCharsetAuto
                && stderrCharsetAuto == that.stderrCharsetAuto;
    }

    @Override
    public int hashCode() {
        int result =
                Objects.hash(
                        policy,
                        command,
                        environment,
                        stdoutCharset,
                        stderrCharset,
                        stdoutCharsetAuto,
                        stderrCharsetAuto);
        return 31 * result + Arrays.hashCode(standardInput);
    }

    @Override
    public String toString() {
        return "SandboxRequest[policy="
                + policy
                + ", command="
                + command
                + ", environmentKeys="
                + environment.keySet()
                + ", standardInputBytes="
                + standardInput.length
                + "]";
    }
}
