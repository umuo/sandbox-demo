package io.github.sandboxdemo.platform.windows;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;
import io.github.sandboxdemo.api.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;

/** Read back ACL contents without account-name lookup or depending on unused ACL padding bytes. */
final class WindowsAclEvidence {
    private WindowsAclEvidence() {}

    static byte[] bytes(Pointer acl) {
        if (acl == null) {
            throw new IllegalArgumentException("NULL ACL has no cleanup proof");
        }
        int size = Short.toUnsignedInt(acl.getShort(2));
        int count = Short.toUnsignedInt(acl.getShort(4));
        if (size < 8 || count > (size - 8) / 4) {
            throw new IllegalArgumentException("invalid ACL");
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(acl.getByte(0));
        int offset = 8;
        for (int i = 0; i < count; i++) {
            if (offset + 4 > size) {
                throw new IllegalArgumentException("truncated ACE");
            }
            int length = Short.toUnsignedInt(acl.getShort(offset + 2));
            if (length < 4 || offset + length > size) {
                throw new IllegalArgumentException("invalid ACE");
            }
            byte[] ace = acl.getByteArray(offset, length);
            result.write(ace, 0, ace.length);
            offset += length;
        }
        return result.toByteArray();
    }

    static boolean containsSid(Pointer acl, String sid) {
        String[] parts = sid.split("-");
        if (parts.length < 4 || !"S".equals(parts[0])) {
            throw new IllegalArgumentException("invalid SID");
        }
        ByteBuffer binary =
                ByteBuffer.allocate(8 + (parts.length - 3) * 4).order(ByteOrder.LITTLE_ENDIAN);
        binary.put((byte) Integer.parseInt(parts[1])).put((byte) (parts.length - 3));
        long authority = Long.parseLong(parts[2]);
        for (int i = 5; i >= 0; i--) {
            binary.put((byte) (authority >>> (i * 8)));
        }
        for (int i = 3; i < parts.length; i++) {
            binary.putInt((int) Long.parseLong(parts[i]));
        }
        byte[] haystack = bytes(acl), needle = binary.array();
        // Unknown ACE formats are treated conservatively: any occurrence prevents confirmation.
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean named(Path path, byte[] expected, Boolean protectedAcl, String absentSid)
            throws SandboxException {
        PointerByReference acl = new PointerByReference(), descriptor = new PointerByReference();
        int error =
                WindowsNative.Advapi32.INSTANCE.GetNamedSecurityInfoW(
                        new WString(path.toString()), 1, 4, null, null, acl, null, descriptor);
        if (error != 0) {
            throw failure();
        }
        try {
            if (absentSid != null && containsSid(acl.getValue(), absentSid)) {
                return false;
            }
            if (expected != null && !Arrays.equals(expected, bytes(acl.getValue()))) {
                return false;
            }
            if (protectedAcl != null) {
                ShortByReference control = new ShortByReference();
                if (!WindowsNative.Advapi32.INSTANCE.GetSecurityDescriptorControl(
                        descriptor.getValue(), control, new IntByReference())) {
                    throw failure();
                }
                if (((Short.toUnsignedInt(control.getValue()) & 0x1000) != 0) != protectedAcl) {
                    return false;
                }
            }
            return true;
        } finally {
            WindowsCleanup.localFree(descriptor.getValue());
        }
    }

    static boolean station(Pointer station, String absentSid) throws SandboxException {
        PointerByReference acl = new PointerByReference(), descriptor = new PointerByReference();
        int error =
                WindowsNative.Advapi32.INSTANCE.GetSecurityInfo(
                        station, 7, 4, null, null, acl, null, descriptor);
        if (error != 0) {
            throw failure();
        }
        try {
            return !containsSid(acl.getValue(), absentSid);
        } finally {
            WindowsCleanup.localFree(descriptor.getValue());
        }
    }

    private static SandboxException failure() {
        return new SandboxException(
                SandboxErrorCode.CLEANUP_FAILED,
                SandboxPhase.CLEANUP,
                "could not independently read back host ACL cleanup");
    }
}
