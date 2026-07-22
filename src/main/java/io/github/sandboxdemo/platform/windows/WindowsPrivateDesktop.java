package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Advapi32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.User32;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.github.sandboxdemo.api.SandboxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Private Win32 desktop preventing restricted GUI/message interaction with the host desktop. */
final class WindowsPrivateDesktop implements AutoCloseable {

    private static final int SECURITY_DESCRIPTOR_REVISION = 1;
    private static final int DESKTOP_ALL_ACCESS = 0x000F01FF;
    private static final int GENERIC_ALL = 0x10000000;
    private static final int SE_WINDOW_OBJECT = 7;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int GRANT_ACCESS = 1;
    private static final int REVOKE_ACCESS = 4;
    private static final int NO_MULTIPLE_TRUSTEE = 0;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_UNKNOWN = 0;
    private static final int UOI_NAME = 2;
    private static final int INFINITE = -1;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_ABANDONED = 0x00000080;

    private final String userSid;
    private final Pointer windowStation;
    private final List<String> stationCapabilitySids;
    private final Pointer desktop;
    private final Memory startupName;
    private boolean closed;

    private WindowsPrivateDesktop(
            String userSid,
            Pointer windowStation,
            List<String> stationCapabilitySids,
            Pointer desktop,
            Memory startupName) {
        this.userSid = userSid;
        this.windowStation = windowStation;
        this.stationCapabilitySids =
                io.github.sandboxdemo.core.Java8.copyList(stationCapabilitySids);
        this.desktop = desktop;
        this.startupName = startupName;
    }

    static WindowsPrivateDesktop create(String userSid, List<String> capabilitySids)
            throws SandboxException {
        Pointer windowStation = null;
        Pointer desktop = null;
        List<String> grantedSids = new ArrayList<>();
        boolean completed = false;
        PointerByReference descriptor = new PointerByReference();
        try {
            windowStation = User32.INSTANCE.GetProcessWindowStation();
            if (isInvalid(windowStation)) {
                throw win32("GetProcessWindowStation");
            }
            String windowStationName = userObjectName(windowStation);
            // CreateProcessAsUser requires access to both objects named by STARTUPINFO.lpDesktop.
            // With WRITE_RESTRICTED, the capability SID participates in the station's write
            // access check as well as the private desktop's check.
            Pointer aclMutex = acquireMutex(userSid);
            try {
                for (String sid : capabilitySids) {
                    updateWindowStationAcl(windowStation, sid, GRANT_ACCESS);
                    grantedSids.add(sid);
                }
            } finally {
                releaseMutex(aclMutex);
            }

            String desktopName =
                    "JavaAgentSandbox-" + UUID.randomUUID().toString().replace("-", "");
            StringBuilder sddl =
                    new StringBuilder("D:(A;;GA;;;SY)(A;;GA;;;").append(userSid).append(')');
            for (String sid : capabilitySids) {
                sddl.append("(A;;GA;;;").append(sid).append(')');
            }
            if (!Advapi32.INSTANCE.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    new WString(sddl.toString()), SECURITY_DESCRIPTOR_REVISION, descriptor, null)) {
                throw win32("ConvertStringSecurityDescriptorToSecurityDescriptorW(desktop)");
            }

            WindowsNative.SECURITY_ATTRIBUTES attributes = new WindowsNative.SECURITY_ATTRIBUTES();
            attributes.nLength = attributes.size();
            attributes.lpSecurityDescriptor = descriptor.getValue();
            attributes.bInheritHandle = 0;
            attributes.write();
            desktop =
                    User32.INSTANCE.CreateDesktopW(
                            new WString(desktopName),
                            null,
                            null,
                            0,
                            DESKTOP_ALL_ACCESS,
                            attributes);
            if (isInvalid(desktop)) {
                throw win32("CreateDesktopW");
            }

            WindowsPrivateDesktop result =
                    new WindowsPrivateDesktop(
                            userSid,
                            windowStation,
                            grantedSids,
                            desktop,
                            WindowsNative.wideString(windowStationName + "\\" + desktopName));
            completed = true;
            return result;
        } finally {
            if (descriptor.getValue() != null) {
                Kernel32.INSTANCE.LocalFree(descriptor.getValue());
            }
            if (!completed) {
                closeDesktop(desktop);
                rollbackWindowStationAcl(userSid, windowStation, grantedSids);
            }
        }
    }

    Pointer startupName() {
        return startupName;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            closeDesktop(desktop);
            rollbackWindowStationAcl(userSid, windowStation, stationCapabilitySids);
        }
    }

    private static void updateWindowStationAcl(Pointer handle, String sid, int accessMode)
            throws SandboxException {
        Pointer convertedSid = null;
        Pointer securityDescriptor = null;
        Pointer newAcl = null;
        try {
            PointerByReference sidReference = new PointerByReference();
            if (!Advapi32.INSTANCE.ConvertStringSidToSidW(new WString(sid), sidReference)) {
                throw win32("ConvertStringSidToSidW(window station)");
            }
            convertedSid = sidReference.getValue();

            PointerByReference oldDacl = new PointerByReference();
            PointerByReference descriptor = new PointerByReference();
            int getError =
                    Advapi32.INSTANCE.GetSecurityInfo(
                            handle,
                            SE_WINDOW_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            oldDacl,
                            null,
                            descriptor);
            if (getError != 0) {
                throw windowsError("GetSecurityInfo(window station)", getError);
            }
            securityDescriptor = descriptor.getValue();
            if (oldDacl.getValue() == null) {
                throw new SandboxException("refusing to mutate a window station with a NULL DACL");
            }

            WindowsNative.EXPLICIT_ACCESS entry = new WindowsNative.EXPLICIT_ACCESS();
            entry.grfAccessPermissions = accessMode == GRANT_ACCESS ? GENERIC_ALL : 0;
            entry.grfAccessMode = accessMode;
            entry.grfInheritance = 0;
            entry.Trustee.pMultipleTrustee = null;
            entry.Trustee.MultipleTrusteeOperation = NO_MULTIPLE_TRUSTEE;
            entry.Trustee.TrusteeForm = TRUSTEE_IS_SID;
            entry.Trustee.TrusteeType = TRUSTEE_IS_UNKNOWN;
            entry.Trustee.ptstrName = convertedSid;
            entry.Trustee.write();
            entry.write();

            PointerByReference updatedAcl = new PointerByReference();
            int mergeError =
                    Advapi32.INSTANCE.SetEntriesInAclW(1, entry, oldDacl.getValue(), updatedAcl);
            if (mergeError != 0) {
                throw windowsError("SetEntriesInAclW(window station)", mergeError);
            }
            newAcl = updatedAcl.getValue();

            int setError =
                    Advapi32.INSTANCE.SetSecurityInfo(
                            handle,
                            SE_WINDOW_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            newAcl,
                            null);
            if (setError != 0) {
                throw windowsError("SetSecurityInfo(window station)", setError);
            }
        } finally {
            if (newAcl != null) {
                Kernel32.INSTANCE.LocalFree(newAcl);
            }
            if (securityDescriptor != null) {
                Kernel32.INSTANCE.LocalFree(securityDescriptor);
            }
            if (convertedSid != null) {
                Kernel32.INSTANCE.LocalFree(convertedSid);
            }
        }
    }

    private static void rollbackWindowStationAcl(
            String userSid, Pointer handle, List<String> grantedSids) {
        if (isInvalid(handle)) {
            return;
        }
        Pointer mutex = null;
        try {
            mutex = acquireMutex(userSid);
            List<String> reverse = new ArrayList<>(grantedSids);
            Collections.reverse(reverse);
            for (String sid : reverse) {
                try {
                    updateWindowStationAcl(handle, sid, REVOKE_ACCESS);
                } catch (Exception ignored) {
                    // Continue removing the other independently generated capability SIDs.
                }
            }
        } catch (Exception ignored) {
            // A leaked random capability ACE is inert because no later token reuses its SID.
        } finally {
            releaseMutex(mutex);
        }
    }

    private static Pointer acquireMutex(String userSid) throws SandboxException {
        String name = "Local\\AgentSandboxSdk-WindowStationAcl-" + userSid;
        Pointer handle = Kernel32.INSTANCE.CreateMutexW(null, false, new WString(name));
        if (isInvalid(handle)) {
            throw win32("CreateMutexW(window station ACL)");
        }
        int wait = Kernel32.INSTANCE.WaitForSingleObject(handle, INFINITE);
        if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) {
            Kernel32.INSTANCE.CloseHandle(handle);
            throw win32("WaitForSingleObject(window station ACL mutex)");
        }
        return handle;
    }

    private static String userObjectName(Pointer handle) throws SandboxException {
        IntByReference needed = new IntByReference();
        User32.INSTANCE.GetUserObjectInformationW(handle, UOI_NAME, null, 0, needed);
        if (needed.getValue() < Native.WCHAR_SIZE) {
            throw win32("GetUserObjectInformationW(window station size)");
        }
        Memory name = new Memory(needed.getValue());
        if (!User32.INSTANCE.GetUserObjectInformationW(
                handle, UOI_NAME, name, (int) name.size(), needed)) {
            throw win32("GetUserObjectInformationW(window station name)");
        }
        return name.getWideString(0);
    }

    private static void releaseMutex(Pointer handle) {
        if (!isInvalid(handle)) {
            Kernel32.INSTANCE.ReleaseMutex(handle);
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private static boolean isInvalid(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == 0;
    }

    private static void closeDesktop(Pointer handle) {
        if (!isInvalid(handle)) {
            User32.INSTANCE.CloseDesktop(handle);
        }
    }

    private static SandboxException win32(String operation) {
        return new SandboxException(operation + " failed, Win32=" + Native.getLastError());
    }

    private static SandboxException windowsError(String operation, int error) {
        return new SandboxException(operation + " failed, Win32=" + error);
    }
}
