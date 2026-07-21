package io.github.sandboxdemo.platform.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.sandboxdemo.api.NetworkPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxSeccompFilterTest {

    @TempDir Path directory;

    @Test
    void denyPolicyRejectsSocketSocketPairAndIoUring() throws Exception {
        byte[] program =
                Files.readAllBytes(LinuxSeccompFilter.write(directory, NetworkPolicy.DENY));

        assertEquals(11 * 8, program.length);
        assertJump(program, 4, expectedSocketSyscall(), 0, 1);
        assertJump(program, 6, expectedSocketPairSyscall(), 0, 1);
        assertJump(program, 8, 425, 0, 1);
        assertEquals(0x00050001, instructionValue(program, 9));
    }

    @Test
    void allowPolicyStillRejectsUnixSocketsAndIoUring() throws Exception {
        byte[] program =
                Files.readAllBytes(LinuxSeccompFilter.write(directory, NetworkPolicy.ALLOW));

        assertEquals(14 * 8, program.length);
        assertJump(program, 4, 425, 0, 1);
        assertJump(program, 6, expectedSocketSyscall(), 3, 0);
        assertJump(program, 7, expectedSocketPairSyscall(), 0, 1);
        assertJump(program, 11, 1, 0, 1); // AF_UNIX
    }

    private static void assertJump(
            byte[] program, int instruction, int value, int jumpTrue, int jumpFalse) {
        int offset = instruction * 8;
        assertEquals(0x15, Short.toUnsignedInt(buffer(program).getShort(offset)));
        assertEquals(jumpTrue, Byte.toUnsignedInt(program[offset + 2]));
        assertEquals(jumpFalse, Byte.toUnsignedInt(program[offset + 3]));
        assertEquals(value, instructionValue(program, instruction));
    }

    private static int instructionValue(byte[] program, int instruction) {
        return buffer(program).getInt(instruction * 8 + 4);
    }

    private static ByteBuffer buffer(byte[] program) {
        return ByteBuffer.wrap(program).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int expectedSocketSyscall() {
        return System.getProperty("os.arch", "").toLowerCase().contains("aarch64")
                        || System.getProperty("os.arch", "").toLowerCase().contains("arm64")
                ? 198
                : 41;
    }

    private static int expectedSocketPairSyscall() {
        return System.getProperty("os.arch", "").toLowerCase().contains("aarch64")
                        || System.getProperty("os.arch", "").toLowerCase().contains("arm64")
                ? 199
                : 53;
    }
}
