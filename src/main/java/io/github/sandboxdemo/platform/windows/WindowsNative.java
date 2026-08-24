package io.github.sandboxdemo.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.ptr.ByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

final class WindowsNative {

    private WindowsNative() {}

    interface Kernel32 extends StdCallLibrary {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer GetCurrentProcess();

        boolean CloseHandle(Pointer handle);

        Pointer LocalFree(Pointer memory);

        int GetFileAttributesW(WString fileName);

        boolean CreatePipe(
                PointerByReference readPipe,
                PointerByReference writePipe,
                SECURITY_ATTRIBUTES attributes,
                int size);

        boolean SetHandleInformation(Pointer object, int mask, int flags);

        Pointer CreateFileW(
                WString fileName,
                int desiredAccess,
                int shareMode,
                SECURITY_ATTRIBUTES securityAttributes,
                int creationDisposition,
                int flagsAndAttributes,
                Pointer templateFile);

        boolean ReadFile(
                Pointer file,
                byte[] buffer,
                int bytesToRead,
                IntByReference bytesRead,
                Pointer overlapped);

        boolean WriteFile(
                Pointer file,
                byte[] buffer,
                int bytesToWrite,
                IntByReference bytesWritten,
                Pointer overlapped);

        boolean InitializeProcThreadAttributeList(
                Pointer attributeList, int attributeCount, int flags, SIZE_TByReference size);

        boolean UpdateProcThreadAttribute(
                Pointer attributeList,
                int flags,
                BaseTSD.ULONG_PTR attribute,
                Pointer value,
                BaseTSD.SIZE_T size,
                Pointer previousValue,
                Pointer returnSize);

        void DeleteProcThreadAttributeList(Pointer attributeList);

        Pointer CreateJobObjectW(Pointer jobAttributes, WString name);

        Pointer CreateMutexW(
                SECURITY_ATTRIBUTES mutexAttributes, boolean initialOwner, WString name);

        boolean ReleaseMutex(Pointer mutex);

        boolean SetInformationJobObject(
                Pointer job, int informationClass, Pointer information, int informationLength);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        boolean TerminateJobObject(Pointer job, int exitCode);

        int ResumeThread(Pointer thread);

        int WaitForSingleObject(Pointer handle, int milliseconds);

        boolean GetExitCodeProcess(Pointer process, IntByReference exitCode);
    }

    interface Advapi32 extends StdCallLibrary {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class, W32APIOptions.UNICODE_OPTIONS);

        boolean OpenProcessToken(
                Pointer processHandle, int desiredAccess, PointerByReference tokenHandle);

        boolean GetTokenInformation(
                Pointer tokenHandle,
                int tokenInformationClass,
                Pointer tokenInformation,
                int tokenInformationLength,
                IntByReference returnLength);

        boolean SetTokenInformation(
                Pointer tokenHandle,
                int tokenInformationClass,
                Pointer tokenInformation,
                int tokenInformationLength);

        boolean ConvertStringSidToSidW(WString stringSid, PointerByReference sid);

        boolean ConvertSidToStringSidW(Pointer sid, PointerByReference stringSid);

        boolean GetSecurityDescriptorControl(
                Pointer securityDescriptor, ShortByReference control, IntByReference revision);

        boolean ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString stringSecurityDescriptor,
                int stringSDRevision,
                PointerByReference securityDescriptor,
                IntByReference securityDescriptorSize);

        int GetNamedSecurityInfoW(
                WString objectName,
                int objectType,
                int securityInfo,
                PointerByReference owner,
                PointerByReference group,
                PointerByReference dacl,
                PointerByReference sacl,
                PointerByReference securityDescriptor);

        int SetNamedSecurityInfoW(
                WString objectName,
                int objectType,
                int securityInfo,
                Pointer owner,
                Pointer group,
                Pointer dacl,
                Pointer sacl);

        int GetSecurityInfo(
                Pointer handle,
                int objectType,
                int securityInfo,
                PointerByReference owner,
                PointerByReference group,
                PointerByReference dacl,
                PointerByReference sacl,
                PointerByReference securityDescriptor);

        int SetSecurityInfo(
                Pointer handle,
                int objectType,
                int securityInfo,
                Pointer owner,
                Pointer group,
                Pointer dacl,
                Pointer sacl);

        int SetEntriesInAclW(
                int explicitEntryCount,
                EXPLICIT_ACCESS explicitEntries,
                Pointer oldAcl,
                PointerByReference newAcl);

        boolean CreateRestrictedToken(
                Pointer existingToken,
                int flags,
                int disableSidCount,
                Pointer sidsToDisable,
                int deletePrivilegeCount,
                Pointer privilegesToDelete,
                int restrictedSidCount,
                Pointer sidsToRestrict,
                PointerByReference newToken);

        boolean CreateProcessAsUserW(
                Pointer token,
                WString applicationName,
                Pointer commandLine,
                Pointer processAttributes,
                Pointer threadAttributes,
                boolean inheritHandles,
                int creationFlags,
                Pointer environment,
                WString currentDirectory,
                STARTUPINFOEX startupInfo,
                PROCESS_INFORMATION processInformation);

        int LsaOpenPolicy(
                LSA_UNICODE_STRING systemName,
                LSA_OBJECT_ATTRIBUTES objectAttributes,
                int desiredAccess,
                PointerByReference policyHandle);

        int LsaAddAccountRights(
                Pointer policyHandle,
                Pointer accountSid,
                LSA_UNICODE_STRING[] userRights,
                int countOfRights);

        int LsaClose(Pointer policyHandle);

        int LsaNtStatusToWinError(int status);

        boolean CreateProcessWithLogonW(
                WString username,
                WString domain,
                WString password,
                int logonFlags,
                WString applicationName,
                Pointer commandLine,
                int creationFlags,
                Pointer environment,
                WString currentDirectory,
                STARTUPINFO startupInfo,
                PROCESS_INFORMATION processInformation);
    }

    interface User32 extends StdCallLibrary {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);

        Pointer GetProcessWindowStation();

        boolean GetUserObjectInformationW(
                Pointer object, int index, Pointer information, int length, IntByReference needed);

        Pointer CreateDesktopW(
                WString desktop,
                WString device,
                Pointer deviceMode,
                int flags,
                int desiredAccess,
                SECURITY_ATTRIBUTES securityAttributes);

        boolean CloseDesktop(Pointer desktop);
    }

    interface Userenv extends StdCallLibrary {
        Userenv INSTANCE = Native.load("userenv", Userenv.class, W32APIOptions.UNICODE_OPTIONS);

        boolean CreateEnvironmentBlock(
                PointerByReference environment, Pointer token, boolean inheritCurrentEnvironment);

        boolean DestroyEnvironmentBlock(Pointer environment);
    }

    @Structure.FieldOrder({"nLength", "lpSecurityDescriptor", "bInheritHandle"})
    public static class SECURITY_ATTRIBUTES extends Structure {
        public int nLength;
        public Pointer lpSecurityDescriptor;
        public int bInheritHandle;
    }

    /**
     * JNA instantiates {@link ByReference} parameters reflectively while preparing a native call.
     * Both the class and its no-argument constructor therefore have to be public, even though the
     * type is only used by this package.
     */
    public static class SIZE_TByReference extends ByReference {

        public SIZE_TByReference() {
            super(Native.POINTER_SIZE);
        }

        BaseTSD.SIZE_T getValue() {
            long value =
                    Native.POINTER_SIZE == Long.BYTES
                            ? getPointer().getLong(0)
                            : Integer.toUnsignedLong(getPointer().getInt(0));
            return new BaseTSD.SIZE_T(value);
        }
    }

    @Structure.FieldOrder({"Sid", "Attributes"})
    public static class SID_AND_ATTRIBUTES extends Structure {
        public Pointer Sid;
        public int Attributes;
    }

    @Structure.FieldOrder({"DefaultDacl"})
    public static class TOKEN_DEFAULT_DACL extends Structure {
        public Pointer DefaultDacl;
    }

    @Structure.FieldOrder({
        "pMultipleTrustee",
        "MultipleTrusteeOperation",
        "TrusteeForm",
        "TrusteeType",
        "ptstrName"
    })
    public static class TRUSTEE extends Structure {
        public Pointer pMultipleTrustee;
        public int MultipleTrusteeOperation;
        public int TrusteeForm;
        public int TrusteeType;
        public Pointer ptstrName;
    }

    @Structure.FieldOrder({"grfAccessPermissions", "grfAccessMode", "grfInheritance", "Trustee"})
    public static class EXPLICIT_ACCESS extends Structure {
        public int grfAccessPermissions;
        public int grfAccessMode;
        public int grfInheritance;
        public TRUSTEE Trustee = new TRUSTEE();
    }

    @Structure.FieldOrder({
        "cb",
        "lpReserved",
        "lpDesktop",
        "lpTitle",
        "dwX",
        "dwY",
        "dwXSize",
        "dwYSize",
        "dwXCountChars",
        "dwYCountChars",
        "dwFillAttribute",
        "dwFlags",
        "wShowWindow",
        "cbReserved2",
        "lpReserved2",
        "hStdInput",
        "hStdOutput",
        "hStdError"
    })
    public static class STARTUPINFO extends Structure {
        public int cb;
        public Pointer lpReserved;
        public Pointer lpDesktop;
        public Pointer lpTitle;
        public int dwX;
        public int dwY;
        public int dwXSize;
        public int dwYSize;
        public int dwXCountChars;
        public int dwYCountChars;
        public int dwFillAttribute;
        public int dwFlags;
        public short wShowWindow;
        public short cbReserved2;
        public Pointer lpReserved2;
        public Pointer hStdInput;
        public Pointer hStdOutput;
        public Pointer hStdError;
    }

    @Structure.FieldOrder({"StartupInfo", "lpAttributeList"})
    public static class STARTUPINFOEX extends Structure {
        public STARTUPINFO StartupInfo = new STARTUPINFO();
        public Pointer lpAttributeList;
    }

    @Structure.FieldOrder({"hProcess", "hThread", "dwProcessId", "dwThreadId"})
    public static class PROCESS_INFORMATION extends Structure {
        public Pointer hProcess;
        public Pointer hThread;
        public int dwProcessId;
        public int dwThreadId;
    }

    @Structure.FieldOrder({
        "Length",
        "RootDirectory",
        "ObjectName",
        "Attributes",
        "SecurityDescriptor",
        "SecurityQualityOfService"
    })
    public static class LSA_OBJECT_ATTRIBUTES extends Structure {
        public int Length;
        public Pointer RootDirectory;
        public Pointer ObjectName;
        public int Attributes;
        public Pointer SecurityDescriptor;
        public Pointer SecurityQualityOfService;

        public LSA_OBJECT_ATTRIBUTES() {
            Length = size();
        }
    }

    @Structure.FieldOrder({"Length", "MaximumLength", "Buffer"})
    public static class LSA_UNICODE_STRING extends Structure {
        public short Length;
        public short MaximumLength;
        public Pointer Buffer;

        public LSA_UNICODE_STRING() {}

        LSA_UNICODE_STRING(Pointer buffer, int lengthBytes) {
            Buffer = buffer;
            Length = (short) lengthBytes;
            MaximumLength = (short) (lengthBytes + Native.WCHAR_SIZE);
        }
    }

    @Structure.FieldOrder({
        "PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags",
        "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit",
        "Affinity", "PriorityClass", "SchedulingClass"
    })
    public static class JOBOBJECT_BASIC_LIMIT_INFORMATION extends Structure {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public int LimitFlags;
        public BaseTSD.SIZE_T MinimumWorkingSetSize = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T MaximumWorkingSetSize = new BaseTSD.SIZE_T();
        public int ActiveProcessLimit;
        public BaseTSD.ULONG_PTR Affinity = new BaseTSD.ULONG_PTR();
        public int PriorityClass;
        public int SchedulingClass;
    }

    @Structure.FieldOrder({
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount"
    })
    public static class IO_COUNTERS extends Structure {
        public long ReadOperationCount;
        public long WriteOperationCount;
        public long OtherOperationCount;
        public long ReadTransferCount;
        public long WriteTransferCount;
        public long OtherTransferCount;
    }

    @Structure.FieldOrder({
        "BasicLimitInformation",
        "IoInfo",
        "ProcessMemoryLimit",
        "JobMemoryLimit",
        "PeakProcessMemoryUsed",
        "PeakJobMemoryUsed"
    })
    public static class JOBOBJECT_EXTENDED_LIMIT_INFORMATION extends Structure {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation =
                new JOBOBJECT_BASIC_LIMIT_INFORMATION();
        public IO_COUNTERS IoInfo = new IO_COUNTERS();
        public BaseTSD.SIZE_T ProcessMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T JobMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T PeakProcessMemoryUsed = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T PeakJobMemoryUsed = new BaseTSD.SIZE_T();
    }

    static Memory wideString(String value) {
        Memory memory = new Memory((long) (value.length() + 1) * Native.WCHAR_SIZE);
        memory.setWideString(0, value);
        return memory;
    }
}
