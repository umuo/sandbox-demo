package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Advapi32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Applies short-lived NTFS capability ACEs and removes them after execution. */
final class WindowsAclManager {

    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;

    private static final int GRANT_ACCESS = 1;
    private static final int DENY_ACCESS = 3;
    private static final int REVOKE_ACCESS = 4;
    private static final int NO_MULTIPLE_TRUSTEE = 0;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_UNKNOWN = 0;
    private static final int OBJECT_INHERIT_ACE = 0x1;
    private static final int CONTAINER_INHERIT_ACE = 0x2;

    private static final int DELETE = 0x00010000;
    private static final int WRITE_DAC = 0x00040000;
    private static final int WRITE_OWNER = 0x00080000;
    private static final int FILE_DELETE_CHILD = 0x00000040;
    // Concrete NTFS file masks from FILE_GENERIC_* in WinNT.h. Generic bits should not be stored
    // directly in an ACE because the object manager maps requested access before the DACL check.
    private static final int FILE_GENERIC_READ = 0x00120089;
    private static final int FILE_GENERIC_WRITE = 0x00120116;
    private static final int FILE_GENERIC_EXECUTE = 0x001200A0;

    private static final int READ_EXECUTE = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
    private static final int MODIFY =
            FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE;
    // Exclude READ_CONTROL and SYNCHRONIZE, which overlap FILE_GENERIC_READ/EXECUTE. Denying the
    // whole FILE_GENERIC_WRITE mask would unintentionally make the protected subtree unreadable.
    private static final int DENY_WRITE =
            0x00000002 // FILE_WRITE_DATA
                    | 0x00000004 // FILE_APPEND_DATA
                    | 0x00000010 // FILE_WRITE_EA
                    | 0x00000100 // FILE_WRITE_ATTRIBUTES
                    | DELETE
                    | FILE_DELETE_CHILD
                    | WRITE_DAC
                    | WRITE_OWNER;

    private static final int INFINITE = -1;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_ABANDONED = 0x00000080;
    private static final String ACL_MUTEX_NAME = "Local\\AgentSandboxSdk-NtfsAcl-v1";

    private final WindowsAclLedger ledger;

    WindowsAclManager() throws SandboxException {
        this(null);
    }

    WindowsAclManager(Path installationHome) throws SandboxException {
        this.ledger = installationHome == null ? null : new WindowsAclLedger(installationHome);
    }

    WindowsAclLease apply(ValidatedPolicy policy) throws SandboxException, InterruptedException {
        return apply(policy, List.of());
    }

    WindowsAclLease apply(ValidatedPolicy policy, List<String> sandboxUserSids)
            throws SandboxException, InterruptedException {
        List<AclMutation> applied = new ArrayList<>();
        List<String> capabilitySids = new ArrayList<>();
        try {
            for (Path readableRoot : policy.readableRoots()) {
                boolean alreadyWritable =
                        policy.writableRoots().stream().anyMatch(readableRoot::startsWith);
                if (alreadyWritable) {
                    continue;
                }
                for (String userSid : sandboxUserSids) {
                    recordPersistentGrant(readableRoot, userSid);
                    grant(readableRoot, userSid, READ_EXECUTE);
                }
            }
            for (Path writableRoot : policy.writableRoots()) {
                String capabilitySid = WindowsCapabilitySid.random();
                grant(writableRoot, capabilitySid, MODIFY);
                applied.add(AclMutation.grant(writableRoot, capabilitySid));
                capabilitySids.add(capabilitySid);

                for (String userSid : sandboxUserSids) {
                    recordPersistentGrant(writableRoot, userSid);
                    grant(writableRoot, userSid, MODIFY);
                }
            }

            // Deny every write-related generic right and deletion on protected roots.
            for (Path protectedPath : policy.protectedPaths()) {
                for (String capabilitySid : capabilitySids) {
                    deny(protectedPath, capabilitySid, DENY_WRITE);
                    applied.add(AclMutation.deny(protectedPath, capabilitySid));
                }
            }
            return new WindowsAclLease(this, capabilitySids, applied);
        } catch (SandboxException e) {
            rollback(applied);
            throw e;
        }
    }

    private void recordPersistentGrant(Path path, String sid) throws SandboxException {
        if (ledger != null) {
            ledger.record(path, sid);
        }
    }

    private static void grant(Path path, String sid, int rights) throws SandboxException {
        update(path, sid, rights, GRANT_ACCESS, OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE);
    }

    private static void deny(Path path, String sid, int rights) throws SandboxException {
        update(path, sid, rights, DENY_ACCESS, OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE);
    }

    private void rollback(List<AclMutation> mutations) {
        List<AclMutation> reverse = new ArrayList<>(mutations);
        Collections.reverse(reverse);
        for (AclMutation mutation : reverse) {
            try {
                remove(mutation);
            } catch (Exception ignored) {
                // A random capability SID left after a crash/rollback is inert because
                // no subsequent restricted token reuses it.
            }
        }
    }

    private void remove(AclMutation mutation) throws SandboxException {
        update(mutation.path(), mutation.sid(), 0, REVOKE_ACCESS, 0);
    }

    /**
     * Updates a DACL without resolving the SID as an account name. This is essential for the
     * one-execution synthetic capability SIDs, which are valid SIDs but intentionally have no LSA
     * account mapping. icacls.exe rejects those SIDs with ERROR_NONE_MAPPED on some Windows hosts.
     */
    private static void update(Path path, String sid, int rights, int accessMode, int inheritance)
            throws SandboxException {
        try (WindowsAclUpdateLock ignored = WindowsAclUpdateLock.acquire()) {
            updateLocked(path, sid, rights, accessMode, inheritance);
        }
    }

    private static void updateLocked(
            Path path, String sid, int rights, int accessMode, int inheritance)
            throws SandboxException {
        Pointer convertedSid = null;
        Pointer securityDescriptor = null;
        Pointer newAcl = null;
        try {
            PointerByReference sidReference = new PointerByReference();
            if (!Advapi32.INSTANCE.ConvertStringSidToSidW(new WString(sid), sidReference)) {
                throw win32("ConvertStringSidToSidW(ACL)");
            }
            convertedSid = sidReference.getValue();

            PointerByReference oldDacl = new PointerByReference();
            PointerByReference descriptor = new PointerByReference();
            int getError =
                    Advapi32.INSTANCE.GetNamedSecurityInfoW(
                            new WString(path.toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            oldDacl,
                            null,
                            descriptor);
            if (getError != 0) {
                throw windowsError("GetNamedSecurityInfoW(" + path + ")", getError);
            }
            securityDescriptor = descriptor.getValue();
            if (oldDacl.getValue() == null) {
                throw new SandboxException(
                        "refusing to mutate an object with a NULL DACL: " + path);
            }

            WindowsNative.EXPLICIT_ACCESS entry = new WindowsNative.EXPLICIT_ACCESS();
            entry.grfAccessPermissions = rights;
            entry.grfAccessMode = accessMode;
            entry.grfInheritance = inheritance;
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
                throw windowsError("SetEntriesInAclW(" + path + ")", mergeError);
            }
            newAcl = updatedAcl.getValue();

            int setError =
                    Advapi32.INSTANCE.SetNamedSecurityInfoW(
                            new WString(path.toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            newAcl,
                            null);
            if (setError != 0) {
                throw windowsError("SetNamedSecurityInfoW(" + path + ")", setError);
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

    private static SandboxException win32(String operation) {
        return new SandboxException(
                operation + " failed, Win32=" + com.sun.jna.Native.getLastError());
    }

    private static SandboxException windowsError(String operation, int error) {
        return new SandboxException(operation + " failed, Win32=" + error);
    }

    /** Serializes read/merge/write DACL mutations across SDK processes in the current session. */
    private static final class WindowsAclUpdateLock implements AutoCloseable {

        private final Pointer mutex;

        private WindowsAclUpdateLock(Pointer mutex) {
            this.mutex = mutex;
        }

        static WindowsAclUpdateLock acquire() throws SandboxException {
            Pointer mutex =
                    Kernel32.INSTANCE.CreateMutexW(null, false, new WString(ACL_MUTEX_NAME));
            if (mutex == null || Pointer.nativeValue(mutex) == 0) {
                throw win32("CreateMutexW(ACL)");
            }
            int wait = Kernel32.INSTANCE.WaitForSingleObject(mutex, INFINITE);
            if (wait != WAIT_OBJECT_0 && wait != WAIT_ABANDONED) {
                Kernel32.INSTANCE.CloseHandle(mutex);
                throw win32("WaitForSingleObject(ACL mutex)");
            }
            return new WindowsAclUpdateLock(mutex);
        }

        @Override
        public void close() {
            Kernel32.INSTANCE.ReleaseMutex(mutex);
            Kernel32.INSTANCE.CloseHandle(mutex);
        }
    }

    static final class WindowsAclLease implements AutoCloseable {

        private final WindowsAclManager manager;
        private final List<String> capabilitySids;
        private final List<AclMutation> mutations;
        private boolean closed;

        private WindowsAclLease(
                WindowsAclManager manager,
                List<String> capabilitySids,
                List<AclMutation> mutations) {
            this.manager = manager;
            this.capabilitySids = List.copyOf(capabilitySids);
            this.mutations = List.copyOf(mutations);
        }

        List<String> capabilitySids() {
            return capabilitySids;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                manager.rollback(mutations);
            }
        }
    }

    private record AclMutation(Path path, String sid, boolean deny) {

        static AclMutation grant(Path path, String sid) {
            return new AclMutation(path, sid, false);
        }

        static AclMutation deny(Path path, String sid) {
            return new AclMutation(path, sid, true);
        }
    }
}
