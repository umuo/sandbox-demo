package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Advapi32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;
import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies short-lived NTFS capability ACEs and removes them after execution. */
final class WindowsAclManager {

    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int PROTECTED_DACL_SECURITY_INFORMATION = 0x80000000;
    private static final int UNPROTECTED_DACL_SECURITY_INFORMATION = 0x20000000;
    private static final int SE_DACL_PROTECTED = 0x1000;

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
    private static final int WRITE_WITH_DELETE =
            FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE;
    private static final int WRITE_WITHOUT_DELETE =
            FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE;
    private static final int DENY_DELETE = DELETE | FILE_DELETE_CHILD;
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
        return apply(policy, io.github.sandboxdemo.core.Java8.listOf());
    }

    WindowsAclLease apply(ValidatedPolicy policy, List<String> sandboxUserSids)
            throws SandboxException, InterruptedException {
        // Temporary denies use an exact DACL snapshot for lossless restoration. Keep the
        // cross-process ACL mutex for the lease lifetime so two overlapping requests cannot
        // restore stale snapshots over one another. Windows mutexes are recursive, so the
        // read/merge/write helpers below can continue taking the same lock defensively.
        WindowsAclUpdateLock leaseLock = WindowsAclUpdateLock.acquire();
        boolean lockTransferred = false;
        List<AclMutation> applied = new ArrayList<>();
        Map<Path, DaclSnapshot> snapshots = new LinkedHashMap<>();
        List<String> capabilitySids = new ArrayList<>();
        List<String> executionSids =
                sandboxUserSids.isEmpty()
                        ? io.github.sandboxdemo.core.Java8.listOf(currentUserSid())
                        : io.github.sandboxdemo.core.Java8.copyList(sandboxUserSids);
        try {
            // Dedicated-user read/write grants are persistent installation state. Apply them before
            // taking temporary DACL snapshots so restoring a request lease retains those grants.
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
                for (String userSid : sandboxUserSids) {
                    recordPersistentGrant(writableRoot, userSid);
                    grant(writableRoot, userSid, writeRights(policy.deletionPolicy()));
                }
            }

            List<Path> readOnlyRoots = readOnlyRoots(policy);
            if (policy.deletionPolicy() == DeletionPolicy.ALLOW) {
                // A deny inherited from a read-only parent must not disable deletion inside an
                // explicitly writable child. Protect the child DACL before the parent deny is
                // propagated; the original inheritance state is restored with the request lease.
                for (Path writableRoot : policy.writableRoots()) {
                    if (readOnlyRoots.stream()
                            .anyMatch(
                                    readOnlyRoot ->
                                            !writableRoot.equals(readOnlyRoot)
                                                    && writableRoot.startsWith(readOnlyRoot))) {
                        DaclSnapshot snapshot = snapshot(snapshots, writableRoot);
                        snapshot.protect();
                    }
                }
            }

            for (Path writableRoot : policy.writableRoots()) {
                String capabilitySid = WindowsCapabilitySid.random();
                int writeRights = writeRights(policy.deletionPolicy());
                grant(writableRoot, capabilitySid, writeRights);
                applied.add(AclMutation.grant(writableRoot, capabilitySid));
                if (policy.deletionPolicy() == DeletionPolicy.DENY) {
                    // An explicit deny is required because compatibility restricting SIDs such as
                    // Everyone may otherwise receive deletion rights from the original DACL.
                    deny(writableRoot, capabilitySid, DENY_DELETE);
                    applied.add(AclMutation.deny(writableRoot, capabilitySid));
                }
                capabilitySids.add(capabilitySid);
            }

            // WRITE_RESTRICTED does not classify the standard DELETE right as FILE_GENERIC_WRITE.
            // A deny against the normal execution identity is therefore required in addition to
            // the capability-SID write check. It covers both DELETE on a child and
            // FILE_DELETE_CHILD on its containing directory.
            for (Path readOnlyRoot : readOnlyRoots) {
                for (String executionSid : executionSids) {
                    snapshot(snapshots, readOnlyRoot);
                    deny(readOnlyRoot, executionSid, DENY_DELETE);
                }
            }

            if (policy.deletionPolicy() == DeletionPolicy.DENY) {
                for (Path writableRoot : policy.writableRoots()) {
                    for (String executionSid : executionSids) {
                        snapshot(snapshots, writableRoot);
                        deny(writableRoot, executionSid, DENY_DELETE);
                    }
                }
            }

            // Deny every write-related generic right and deletion on protected roots.
            for (Path protectedPath : policy.protectedPaths()) {
                for (String capabilitySid : capabilitySids) {
                    deny(protectedPath, capabilitySid, DENY_WRITE);
                    applied.add(AclMutation.deny(protectedPath, capabilitySid));
                }
                for (String executionSid : executionSids) {
                    snapshot(snapshots, protectedPath);
                    deny(protectedPath, executionSid, DENY_DELETE);
                }
            }
            WindowsAclLease lease =
                    new WindowsAclLease(this, capabilitySids, applied, snapshots, leaseLock);
            lockTransferred = true;
            return lease;
        } catch (SandboxException e) {
            rollback(applied, snapshots);
            throw e;
        } catch (RuntimeException | Error e) {
            rollback(applied, snapshots);
            throw e;
        } finally {
            if (!lockTransferred) {
                leaseLock.close();
            }
        }
    }

    private static int writeRights(DeletionPolicy policy) {
        return policy == DeletionPolicy.DENY ? WRITE_WITHOUT_DELETE : WRITE_WITH_DELETE;
    }

    private static List<Path> readOnlyRoots(ValidatedPolicy policy) {
        List<Path> candidates = new ArrayList<>();
        for (Path readableRoot : policy.readableRoots()) {
            boolean coveredByWritable =
                    policy.writableRoots().stream().anyMatch(readableRoot::startsWith);
            if (!coveredByWritable) {
                candidates.add(readableRoot);
            }
        }

        // An ancestor deny already covers its readable descendants. Keeping only minimal roots
        // avoids redundant ACL propagation through the same tree.
        candidates.sort(Comparator.comparingInt(Path::getNameCount));
        List<Path> result = new ArrayList<>();
        for (Path candidate : candidates) {
            if (result.stream().noneMatch(candidate::startsWith)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static DaclSnapshot snapshot(Map<Path, DaclSnapshot> snapshots, Path path)
            throws SandboxException {
        DaclSnapshot existing = snapshots.get(path);
        if (existing != null) {
            return existing;
        }
        DaclSnapshot captured = DaclSnapshot.capture(path);
        snapshots.put(path, captured);
        return captured;
    }

    private static String currentUserSid() throws SandboxException {
        com.sun.jna.platform.win32.WinNT.HANDLEByReference token =
                new com.sun.jna.platform.win32.WinNT.HANDLEByReference();
        if (!com.sun.jna.platform.win32.Advapi32.INSTANCE.OpenProcessToken(
                com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess(),
                com.sun.jna.platform.win32.WinNT.TOKEN_QUERY,
                token)) {
            throw win32("OpenProcessToken(ACL identity)");
        }
        try {
            return com.sun.jna.platform.win32.Advapi32Util.getTokenAccount(token.getValue())
                    .sidString;
        } finally {
            com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(token.getValue());
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

    /**
     * Makes one existing file read-only to {@code sid} without denying the access-control and
     * synchronization bits that Windows also needs when opening the file for reading.
     */
    static void denyFileWrites(Path path, String sid) throws SandboxException {
        update(path, sid, DENY_WRITE, DENY_ACCESS, 0);
    }

    static int readExecuteMaskForTest() {
        return READ_EXECUTE;
    }

    static int denyWriteMaskForTest() {
        return DENY_WRITE;
    }

    static int writeWithoutDeleteMaskForTest() {
        return WRITE_WITHOUT_DELETE;
    }

    static int denyDeleteMaskForTest() {
        return DENY_DELETE;
    }

    private void rollback(List<AclMutation> mutations, Map<Path, DaclSnapshot> snapshots) {
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

        // Restore ancestors before descendants. This removes propagated parent denies first, then
        // reinstates each nested writable root's exact original DACL and inheritance state.
        List<DaclSnapshot> ordered = new ArrayList<>(snapshots.values());
        ordered.sort(Comparator.comparingInt(snapshot -> snapshot.path().getNameCount()));
        for (DaclSnapshot snapshot : ordered) {
            try {
                snapshot.restore();
            } catch (Exception ignored) {
                // The deny never removes WRITE_DAC, so a later host recovery can restore the DACL.
            } finally {
                snapshot.close();
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
        private final Map<Path, DaclSnapshot> snapshots;
        private final WindowsAclUpdateLock leaseLock;
        private boolean closed;

        private WindowsAclLease(
                WindowsAclManager manager,
                List<String> capabilitySids,
                List<AclMutation> mutations,
                Map<Path, DaclSnapshot> snapshots,
                WindowsAclUpdateLock leaseLock) {
            this.manager = manager;
            this.capabilitySids = io.github.sandboxdemo.core.Java8.copyList(capabilitySids);
            this.mutations = io.github.sandboxdemo.core.Java8.copyList(mutations);
            this.snapshots = new LinkedHashMap<>(snapshots);
            this.leaseLock = leaseLock;
        }

        List<String> capabilitySids() {
            return capabilitySids;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    manager.rollback(mutations, snapshots);
                } finally {
                    leaseLock.close();
                }
            }
        }
    }

    /** Holds the original DACL allocation until the temporary policy mutation is restored. */
    private static final class DaclSnapshot implements AutoCloseable {

        private final Path path;
        private final Pointer securityDescriptor;
        private final Pointer dacl;
        private final boolean protectedDacl;
        private boolean closed;

        private DaclSnapshot(
                Path path, Pointer securityDescriptor, Pointer dacl, boolean protectedDacl) {
            this.path = path;
            this.securityDescriptor = securityDescriptor;
            this.dacl = dacl;
            this.protectedDacl = protectedDacl;
        }

        static DaclSnapshot capture(Path path) throws SandboxException {
            PointerByReference dacl = new PointerByReference();
            PointerByReference descriptor = new PointerByReference();
            int getError =
                    Advapi32.INSTANCE.GetNamedSecurityInfoW(
                            new WString(path.toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION,
                            null,
                            null,
                            dacl,
                            null,
                            descriptor);
            if (getError != 0) {
                throw windowsError("GetNamedSecurityInfoW(snapshot, " + path + ")", getError);
            }
            if (dacl.getValue() == null) {
                Kernel32.INSTANCE.LocalFree(descriptor.getValue());
                throw new SandboxException(
                        "refusing to snapshot an object with a NULL DACL: " + path);
            }

            ShortByReference control = new ShortByReference();
            IntByReference revision = new IntByReference();
            if (!Advapi32.INSTANCE.GetSecurityDescriptorControl(
                    descriptor.getValue(), control, revision)) {
                int error = com.sun.jna.Native.getLastError();
                Kernel32.INSTANCE.LocalFree(descriptor.getValue());
                throw new SandboxException(
                        "GetSecurityDescriptorControl(" + path + ") failed, Win32=" + error);
            }
            return new DaclSnapshot(
                    path,
                    descriptor.getValue(),
                    dacl.getValue(),
                    (Short.toUnsignedInt(control.getValue()) & SE_DACL_PROTECTED) != 0);
        }

        Path path() {
            return path;
        }

        void protect() throws SandboxException {
            if (!protectedDacl) {
                set(PROTECTED_DACL_SECURITY_INFORMATION);
            }
        }

        void restore() throws SandboxException {
            set(
                    protectedDacl
                            ? PROTECTED_DACL_SECURITY_INFORMATION
                            : UNPROTECTED_DACL_SECURITY_INFORMATION);
        }

        private void set(int protectionFlag) throws SandboxException {
            int error =
                    Advapi32.INSTANCE.SetNamedSecurityInfoW(
                            new WString(path.toString()),
                            SE_FILE_OBJECT,
                            DACL_SECURITY_INFORMATION | protectionFlag,
                            null,
                            null,
                            dacl,
                            null);
            if (error != 0) {
                throw windowsError("SetNamedSecurityInfoW(snapshot, " + path + ")", error);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                Kernel32.INSTANCE.LocalFree(securityDescriptor);
            }
        }
    }

    private static final class AclMutation {

        private final Path path;
        private final String sid;
        private final boolean deny;

        private AclMutation(Path path, String sid, boolean deny) {
            this.path = path;
            this.sid = sid;
            this.deny = deny;
        }

        Path path() {
            return path;
        }

        String sid() {
            return sid;
        }

        boolean deny() {
            return deny;
        }

        static AclMutation grant(Path path, String sid) {
            return new AclMutation(path, sid, false);
        }

        static AclMutation deny(Path path, String sid) {
            return new AclMutation(path, sid, true);
        }
    }
}
