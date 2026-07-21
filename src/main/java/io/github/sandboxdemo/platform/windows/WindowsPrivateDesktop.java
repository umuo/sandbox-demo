package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Advapi32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.User32;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import io.github.sandboxdemo.api.SandboxException;
import java.util.List;
import java.util.UUID;

/** Private Win32 desktop preventing restricted GUI/message interaction with the host desktop. */
final class WindowsPrivateDesktop implements AutoCloseable {

    private static final int SECURITY_DESCRIPTOR_REVISION = 1;
    private static final int DESKTOP_ALL_ACCESS = 0x000F01FF;

    private final Pointer handle;
    private final Memory startupName;

    private WindowsPrivateDesktop(Pointer handle, Memory startupName) {
        this.handle = handle;
        this.startupName = startupName;
    }

    static WindowsPrivateDesktop create(String userSid, List<String> capabilitySids)
            throws SandboxException {
        String name = "JavaAgentSandbox-" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sddl = new StringBuilder("D:(A;;GA;;;").append(userSid).append(')');
        for (String sid : capabilitySids) {
            sddl.append("(A;;GA;;;").append(sid).append(')');
        }

        PointerByReference descriptor = new PointerByReference();
        if (!Advapi32.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                new WString(sddl.toString()), SECURITY_DESCRIPTOR_REVISION, descriptor, null)) {
            throw win32("ConvertStringSecurityDescriptorToSecurityDescriptorW(desktop)");
        }
        try {
            WindowsNative.SECURITY_ATTRIBUTES attributes = new WindowsNative.SECURITY_ATTRIBUTES();
            attributes.nLength = attributes.size();
            attributes.lpSecurityDescriptor = descriptor.getValue();
            attributes.bInheritHandle = 0;
            attributes.write();
            Pointer desktop =
                    User32.INSTANCE.CreateDesktopW(
                            new WString(name), null, null, 0, DESKTOP_ALL_ACCESS, attributes);
            if (desktop == null || Pointer.nativeValue(desktop) == 0) {
                throw win32("CreateDesktopW");
            }
            return new WindowsPrivateDesktop(desktop, WindowsNative.wideString("Winsta0\\" + name));
        } finally {
            Kernel32.INSTANCE.LocalFree(descriptor.getValue());
        }
    }

    Pointer startupName() {
        return startupName;
    }

    @Override
    public void close() {
        if (handle != null && Pointer.nativeValue(handle) != 0) {
            User32.INSTANCE.CloseDesktop(handle);
        }
    }

    private static SandboxException win32(String operation) {
        return new SandboxException(operation + " failed, Win32=" + Native.getLastError());
    }
}
