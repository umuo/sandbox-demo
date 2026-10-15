package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Length-prefixed, versioned file protocol between the host and Windows worker. */
final class WindowsWorkerProtocol {

    private static final int REQUEST_MAGIC = 0x53425851;
    private static final int RESULT_MAGIC = 0x53425852;
    static final int VERSION = 7;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_BINARY_BYTES = 64 * 1024 * 1024;
    private static final int MAX_STANDARD_INPUT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_PATHS = 256;
    private static final int MAX_ENVIRONMENT = 512;

    private WindowsWorkerProtocol() {}

    static void writeRequest(Path file, WorkerRequest request) throws SandboxException {
        try (DataOutputStream output =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(REQUEST_MAGIC);
            output.writeInt(VERSION);
            writeString(output, request.expectedUserSid());
            writeString(output, request.executable().toString());
            writeStrings(output, request.arguments());
            writeBytes(output, request.standardInput());
            writeString(output, request.policy().workingDirectory().toString());
            writePaths(output, request.policy().readableRoots());
            writePaths(output, request.policy().writableRoots());
            writePaths(output, request.policy().protectedPaths());
            writeString(output, request.policy().privateTempDirectory().toString());
            output.writeInt(request.policy().networkPolicy().ordinal());
            output.writeInt(request.policy().readPolicy().ordinal());
            output.writeInt(request.policy().deletionPolicy().ordinal());
            output.writeLong(request.policy().timeout().toMillis());
            output.writeInt(request.policy().maxOutputBytes());
            output.writeBoolean(request.policy().allowPathSearch());
            output.writeInt(request.policy().onOutputLimit().ordinal());
            output.writeInt(request.policy().maxCombinedOutputBytes());
            output.writeInt(request.policy().outputCallbackMode().ordinal());
            output.writeLong(request.policy().resourceLimits().memoryBytes());
            output.writeInt(request.policy().resourceLimits().processes());
            output.writeLong(request.policy().resourceLimits().cpuTimeMillis());
            output.writeLong(request.policy().resourceLimits().diskBytes());
            writeString(output, request.policy().requiredIsolation().name());
            writeMap(output, request.environment());
            writeStrings(output, request.capabilitySids());
        } catch (IOException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "failed to write Windows worker request",
                    e);
        }
    }

    static WorkerRequest readRequest(Path file) throws SandboxException {
        try (DataInputStream input =
                new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            requireHeader(input, REQUEST_MAGIC);
            String expectedSid = readString(input);
            Path executable = java.nio.file.Paths.get(readString(input));
            List<String> arguments = readStrings(input, MAX_ARGUMENTS);
            byte[] standardInput = readBytes(input, MAX_STANDARD_INPUT_BYTES);
            Path cwd = java.nio.file.Paths.get(readString(input));
            List<Path> readable = readPaths(input);
            List<Path> writable = readPaths(input);
            List<Path> protectedPaths = readPaths(input);
            Path temp = java.nio.file.Paths.get(readString(input));
            int networkOrdinal = input.readInt();
            if (networkOrdinal < 0 || networkOrdinal >= NetworkPolicy.values().length) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "invalid network policy in Windows worker request");
            }
            int readOrdinal = input.readInt();
            if (readOrdinal < 0 || readOrdinal >= ReadPolicy.values().length) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "invalid read policy in Windows worker request");
            }
            int deletionOrdinal = input.readInt();
            if (deletionOrdinal < 0 || deletionOrdinal >= DeletionPolicy.values().length) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "invalid deletion policy in Windows worker request");
            }
            long timeoutMillis = input.readLong();
            int maxOutputBytes = input.readInt();
            boolean allowPathSearch = input.readBoolean();
            int action = input.readInt();
            int combined = input.readInt();
            int callback = input.readInt();
            if (action < 0
                    || action >= io.github.sandboxdemo.api.OutputLimitAction.values().length
                    || callback < 0
                    || callback >= io.github.sandboxdemo.api.OutputCallbackMode.values().length
                    || combined < 0
                    || combined > 128 * 1024 * 1024) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "invalid output policy in Windows worker request");
            }
            if (timeoutMillis < 1
                    || timeoutMillis > Duration.ofHours(24).toMillis()
                    || maxOutputBytes < 1
                    || maxOutputBytes > MAX_BINARY_BYTES) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "invalid limits in Windows worker request");
            }
            long memory = input.readLong();
            int processes = input.readInt();
            long cpu = input.readLong(), disk = input.readLong();
            io.github.sandboxdemo.api.SandboxIsolationLevel isolation =
                    io.github.sandboxdemo.api.SandboxIsolationLevel.valueOf(readString(input));
            if (memory < 0 || processes < 0 || cpu < 0 || disk < 0) {
                throw new IllegalArgumentException("negative resource limit");
            }
            io.github.sandboxdemo.api.SandboxResourceLimits.Builder resourceBuilder =
                    io.github.sandboxdemo.api.SandboxResourceLimits.builder();
            if (memory > 0) {
                resourceBuilder.memoryBytes(memory);
            }
            if (processes > 0) {
                resourceBuilder.processes(processes);
            }
            if (cpu > 0) {
                resourceBuilder.cpuTime(Duration.ofMillis(cpu));
            }
            if (disk > 0) {
                resourceBuilder.diskBytes(disk);
            }
            Map<String, String> environment = readMap(input);
            List<String> capabilitySids = readStrings(input, MAX_PATHS);
            if (capabilitySids.isEmpty()) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "Windows worker request has no capability SID");
            }
            ValidatedPolicy policy =
                    new ValidatedPolicy(
                            cwd,
                            readable,
                            writable,
                            protectedPaths,
                            temp,
                            NetworkPolicy.values()[networkOrdinal],
                            ReadPolicy.values()[readOrdinal],
                            DeletionPolicy.values()[deletionOrdinal],
                            Duration.ofMillis(timeoutMillis),
                            maxOutputBytes,
                            allowPathSearch,
                            io.github.sandboxdemo.api.OutputLimitAction.values()[action],
                            combined,
                            io.github.sandboxdemo.api.OutputCallbackMode.values()[callback],
                            resourceBuilder.build(),
                            isolation);
            return new WorkerRequest(
                    expectedSid,
                    executable,
                    arguments,
                    standardInput,
                    policy,
                    environment,
                    capabilitySids);
        } catch (EOFException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "truncated Windows worker request",
                    e);
        } catch (IOException | IllegalArgumentException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "failed to read Windows worker request ("
                            + e.getClass().getSimpleName()
                            + ": "
                            + safeMessage(e)
                            + ")",
                    e);
        }
    }

    static void writeResult(Path file, SandboxResult result, String workerError)
            throws SandboxException {
        try (DataOutputStream output =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(RESULT_MAGIC);
            output.writeInt(VERSION);
            output.writeBoolean(workerError != null);
            if (workerError != null) {
                writeString(output, io.github.sandboxdemo.api.SandboxErrorCode.UNKNOWN.name());
                writeString(output, io.github.sandboxdemo.api.SandboxPhase.EXECUTION.name());
                writeString(output, workerError);
                return;
            }
            output.writeInt(result.exitCode());
            output.writeBoolean(result.timedOut());
            writeBytes(output, result.stdout());
            writeBytes(output, result.stderr());
            output.writeBoolean(result.stdoutTruncated());
            output.writeBoolean(result.stderrTruncated());
            output.writeLong(result.duration().toNanos());
            output.writeBoolean(result.outputLimitTerminated());
        } catch (IOException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                    io.github.sandboxdemo.api.SandboxPhase.OUTPUT,
                    "failed to write Windows worker result",
                    e);
        }
    }

    static void writeFailure(Path file, SandboxException failure) throws SandboxException {
        try (DataOutputStream output =
                new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(RESULT_MAGIC);
            output.writeInt(VERSION);
            output.writeBoolean(true);
            writeString(output, failure.code().name());
            writeString(output, failure.phase().name());
            String diagnostic = failure.debugMessage();
            writeString(
                    output,
                    diagnostic.length() <= 2000 ? diagnostic : diagnostic.substring(0, 2000));
        } catch (IOException error) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                    io.github.sandboxdemo.api.SandboxPhase.OUTPUT,
                    "failed to write worker failure",
                    error);
        }
    }

    static SandboxResult readResult(Path file) throws SandboxException {
        try (DataInputStream input =
                new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            requireHeader(input, RESULT_MAGIC);
            if (input.readBoolean()) {
                String code = readString(input),
                        phase = readString(input),
                        diagnostic = readString(input);
                try {
                    throw new SandboxException(
                            io.github.sandboxdemo.api.SandboxErrorCode.valueOf(code),
                            io.github.sandboxdemo.api.SandboxPhase.valueOf(phase),
                            diagnostic);
                } catch (IllegalArgumentException invalid) {
                    throw new SandboxException(
                            io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                            io.github.sandboxdemo.api.SandboxPhase.OUTPUT,
                            "invalid worker error fields",
                            invalid);
                }
            }
            int exitCode = input.readInt();
            boolean timedOut = input.readBoolean();
            byte[] stdout = readBytes(input, MAX_BINARY_BYTES);
            byte[] stderr = readBytes(input, MAX_BINARY_BYTES);
            boolean stdoutTruncated = input.readBoolean();
            boolean stderrTruncated = input.readBoolean();
            long durationNanos = input.readLong();
            return new SandboxResult(
                    exitCode,
                    timedOut,
                    stdout,
                    stderr,
                    stdoutTruncated,
                    stderrTruncated,
                    Duration.ofNanos(Math.max(0, durationNanos)),
                    input.readBoolean());
        } catch (EOFException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                    io.github.sandboxdemo.api.SandboxPhase.OUTPUT,
                    "truncated Windows worker result",
                    e);
        } catch (IOException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                    io.github.sandboxdemo.api.SandboxPhase.OUTPUT,
                    "failed to read Windows worker result",
                    e);
        }
    }

    private static void requireHeader(DataInputStream input, int expected)
            throws IOException, SandboxException {
        if (input.readInt() != expected || input.readInt() != VERSION) {
            throw new SandboxException(
                    expected == REQUEST_MAGIC
                            ? io.github.sandboxdemo.api.SandboxErrorCode.BACKEND_UNAVAILABLE
                            : io.github.sandboxdemo.api.SandboxErrorCode.OUTPUT_CAPTURE_FAILED,
                    expected == REQUEST_MAGIC
                            ? io.github.sandboxdemo.api.SandboxPhase.PREPARATION
                            : io.github.sandboxdemo.api.SandboxPhase.OUTPUT,
                    "incompatible Windows worker protocol");
        }
    }

    private static void writePaths(DataOutputStream output, List<Path> paths) throws IOException {
        output.writeInt(paths.size());
        for (Path path : paths) {
            writeString(output, path.toString());
        }
    }

    private static List<Path> readPaths(DataInputStream input)
            throws IOException, SandboxException {
        List<String> values = readStrings(input, MAX_PATHS);
        List<Path> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(java.nio.file.Paths.get(value));
        }
        return io.github.sandboxdemo.core.Java8.copyList(result);
    }

    private static void writeMap(DataOutputStream output, Map<String, String> values)
            throws IOException {
        output.writeInt(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            writeString(output, entry.getKey());
            writeString(output, entry.getValue());
        }
    }

    private static Map<String, String> readMap(DataInputStream input)
            throws IOException, SandboxException {
        int count = readCount(input, MAX_ENVIRONMENT, "environment entries");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String previous = result.put(readString(input), readString(input));
            if (previous != null) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "duplicate environment variable in worker request");
            }
        }
        return io.github.sandboxdemo.core.Java8.copyMap(result);
    }

    private static void writeStrings(DataOutputStream output, List<String> values)
            throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static List<String> readStrings(DataInputStream input, int limit)
            throws IOException, SandboxException {
        int count = readCount(input, limit, "list entries");
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(readString(input));
        }
        return io.github.sandboxdemo.core.Java8.copyList(result);
    }

    private static int readCount(DataInputStream input, int maximum, String description)
            throws IOException, SandboxException {
        int count = input.readInt();
        if (count < 0 || count > maximum) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "invalid number of " + description + ": " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream input) throws IOException, SandboxException {
        return new String(readBytes(input, MAX_STRING_BYTES), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static byte[] readBytes(DataInputStream input, int maximum)
            throws IOException, SandboxException {
        int length = input.readInt();
        if (length < 0 || length > maximum) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "invalid binary field length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || io.github.sandboxdemo.core.Java8.isBlank(message)) {
            return "no error message";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    static final class WorkerRequest {

        private final String expectedUserSid;
        private final Path executable;
        private final List<String> arguments;
        private final byte[] standardInput;
        private final ValidatedPolicy policy;
        private final Map<String, String> environment;
        private final List<String> capabilitySids;

        WorkerRequest(
                String expectedUserSid,
                Path executable,
                List<String> arguments,
                byte[] standardInput,
                ValidatedPolicy policy,
                Map<String, String> environment,
                List<String> capabilitySids) {
            this.expectedUserSid = expectedUserSid;
            this.executable = executable;
            this.arguments = arguments;
            this.standardInput = java.util.Arrays.copyOf(standardInput, standardInput.length);
            this.policy = policy;
            this.environment = environment;
            this.capabilitySids = capabilitySids;
        }

        String expectedUserSid() {
            return expectedUserSid;
        }

        Path executable() {
            return executable;
        }

        List<String> arguments() {
            return arguments;
        }

        public byte[] standardInput() {
            return java.util.Arrays.copyOf(standardInput, standardInput.length);
        }

        ValidatedPolicy policy() {
            return policy;
        }

        Map<String, String> environment() {
            return environment;
        }

        List<String> capabilitySids() {
            return capabilitySids;
        }
    }
}
