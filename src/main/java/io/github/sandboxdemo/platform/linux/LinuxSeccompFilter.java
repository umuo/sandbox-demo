package io.github.sandboxdemo.platform.linux;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Builds a small classic-BPF seccomp program that denies host Unix-domain socket access. Filesystem
 * read-only mounts do not make a mounted socket safe: connecting to Docker, D-Bus, SSH-agent, or a
 * similar host socket can cross the intended sandbox boundary.
 */
final class LinuxSeccompFilter {

    private static final short BPF_LOAD_WORD_ABSOLUTE = 0x20;
    private static final short BPF_JUMP_EQUAL = 0x15;
    private static final short BPF_RETURN = 0x06;

    private static final int SECCOMP_DATA_ARCH_OFFSET = 4;
    private static final int SECCOMP_DATA_ARGUMENT_0_OFFSET = 16;
    private static final int SECCOMP_RETURN_KILL_PROCESS = 0x80000000;
    private static final int SECCOMP_RETURN_ERRNO = 0x00050000;
    private static final int SECCOMP_RETURN_ALLOW = 0x7fff0000;
    private static final int EPERM = 1;
    private static final int AF_UNIX = 1;

    private LinuxSeccompFilter() {}

    static Path write(Path directory, NetworkPolicy networkPolicy)
            throws SandboxBackendUnavailableException {
        Architecture architecture = Architecture.current();
        List<Instruction> instructions =
                networkPolicy == NetworkPolicy.DENY
                        ? denyAllNetwork(architecture)
                        : denyHostUnixSockets(architecture);

        ByteBuffer bytes =
                ByteBuffer.allocate(instructions.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
        instructions.forEach(instruction -> instruction.write(bytes));
        Path filter = directory.resolve("deny-host-unix-sockets.bpf");
        try {
            Files.write(filter, bytes.array());
            return filter;
        } catch (IOException e) {
            throw new SandboxBackendUnavailableException(
                    "failed to create the Linux seccomp policy: " + e.getMessage());
        }
    }

    private static List<Instruction> denyAllNetwork(Architecture architecture) {
        return io.github.sandboxdemo.core.Java8.listOf(
                load(SECCOMP_DATA_ARCH_OFFSET),
                jump(architecture.auditArchitecture(), 1, 0),
                result(SECCOMP_RETURN_KILL_PROCESS),
                load(0),
                jump(architecture.socketSyscall(), 0, 1),
                result(SECCOMP_RETURN_ERRNO | EPERM),
                jump(architecture.socketPairSyscall(), 0, 1),
                result(SECCOMP_RETURN_ERRNO | EPERM),
                jump(architecture.ioUringSetupSyscall(), 0, 1),
                result(SECCOMP_RETURN_ERRNO | EPERM),
                result(SECCOMP_RETURN_ALLOW));
    }

    private static List<Instruction> denyHostUnixSockets(Architecture architecture) {
        return io.github.sandboxdemo.core.Java8.listOf(
                load(SECCOMP_DATA_ARCH_OFFSET),
                jump(architecture.auditArchitecture(), 1, 0),
                result(SECCOMP_RETURN_KILL_PROCESS),
                load(0),
                jump(architecture.ioUringSetupSyscall(), 0, 1),
                result(SECCOMP_RETURN_ERRNO | EPERM),
                jump(architecture.socketSyscall(), 3, 0),
                jump(architecture.socketPairSyscall(), 0, 1),
                result(SECCOMP_RETURN_ERRNO | EPERM),
                result(SECCOMP_RETURN_ALLOW),
                load(SECCOMP_DATA_ARGUMENT_0_OFFSET),
                jump(AF_UNIX, 0, 1),
                result(SECCOMP_RETURN_ERRNO | EPERM),
                result(SECCOMP_RETURN_ALLOW));
    }

    private static Instruction load(int offset) {
        return new Instruction(BPF_LOAD_WORD_ABSOLUTE, 0, 0, offset);
    }

    private static Instruction jump(int value, int jumpTrue, int jumpFalse) {
        return new Instruction(BPF_JUMP_EQUAL, jumpTrue, jumpFalse, value);
    }

    private static Instruction result(int value) {
        return new Instruction(BPF_RETURN, 0, 0, value);
    }

    private static final class Instruction {

        private final short code;
        private final int jumpTrue;
        private final int jumpFalse;
        private final int value;

        private Instruction(short code, int jumpTrue, int jumpFalse, int value) {
            this.code = code;
            this.jumpTrue = jumpTrue;
            this.jumpFalse = jumpFalse;
            this.value = value;
        }

        void write(ByteBuffer output) {
            output.putShort(code);
            output.put((byte) jumpTrue);
            output.put((byte) jumpFalse);
            output.putInt(value);
        }
    }

    private static final class Architecture {

        private static final int AUDIT_ARCH_X86_64 = 0xC000003E;
        private static final int AUDIT_ARCH_AARCH64 = 0xC00000B7;

        private final int auditArchitecture;
        private final int socketSyscall;
        private final int socketPairSyscall;
        private final int ioUringSetupSyscall;

        private Architecture(
                int auditArchitecture,
                int socketSyscall,
                int socketPairSyscall,
                int ioUringSetupSyscall) {
            this.auditArchitecture = auditArchitecture;
            this.socketSyscall = socketSyscall;
            this.socketPairSyscall = socketPairSyscall;
            this.ioUringSetupSyscall = ioUringSetupSyscall;
        }

        int auditArchitecture() {
            return auditArchitecture;
        }

        int socketSyscall() {
            return socketSyscall;
        }

        int socketPairSyscall() {
            return socketPairSyscall;
        }

        int ioUringSetupSyscall() {
            return ioUringSetupSyscall;
        }

        static Architecture current() throws SandboxBackendUnavailableException {
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if ("amd64".equals(architecture) || "x86_64".equals(architecture)) {
                return new Architecture(AUDIT_ARCH_X86_64, 41, 53, 425);
            }
            if ("aarch64".equals(architecture) || "arm64".equals(architecture)) {
                return new Architecture(AUDIT_ARCH_AARCH64, 198, 199, 425);
            }
            throw new SandboxBackendUnavailableException(
                    "the Linux seccomp policy supports x86_64 and aarch64 only: " + architecture);
        }
    }
}
