package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rejects NTFS reparse points and retains non-delete-sharing handles for the important policy roots
 * while a command is running.
 */
final class WindowsPathLease implements AutoCloseable {

    private static final int INVALID_FILE_ATTRIBUTES = -1;
    private static final int FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400;
    private static final int FILE_READ_ATTRIBUTES = 0x00000080;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int FILE_SHARE_WRITE = 0x00000002;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    private static final int FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;

    private final List<Pointer> handles;

    private WindowsPathLease(List<Pointer> handles) {
        this.handles = handles;
    }

    static WindowsPathLease acquire(ValidatedPolicy policy, Path executable)
            throws SandboxException {
        List<Path> protectedObjects = new ArrayList<>();
        protectedObjects.add(policy.workingDirectory());
        protectedObjects.addAll(policy.readableRoots());
        protectedObjects.addAll(policy.writableRoots());
        protectedObjects.addAll(policy.protectedPaths());
        protectedObjects.add(policy.privateTempDirectory());

        List<Pointer> handles = new ArrayList<>();
        try {
            for (Path path :
                    protectedObjects.stream()
                            .distinct()
                            .collect(java.util.stream.Collectors.toList())) {
                rejectRemoteOrNonNtfs(path);
                rejectReparseComponents(path);
                handles.add(openWithoutDeleteSharing(path));
            }
            rejectRemoteOrNonNtfs(executable);
            rejectReparseComponents(executable);
            handles.add(openWithoutWriteOrDeleteSharing(executable));
            return new WindowsPathLease(handles);
        } catch (SandboxException e) {
            closeAll(handles);
            throw e;
        }
    }

    private static void rejectRemoteOrNonNtfs(Path path) throws SandboxException {
        if (path.toString().startsWith("\\\\")) {
            throw new SandboxException(
                    "UNC paths are not accepted by the Windows sandbox: " + path);
        }
        try {
            String type = Files.getFileStore(path).type();
            if (!"NTFS".equalsIgnoreCase(type)) {
                throw new SandboxException(
                        "Windows sandbox paths must reside on NTFS; " + path + " uses " + type);
            }
        } catch (IOException e) {
            throw new SandboxException("failed to inspect the Windows filesystem for: " + path, e);
        }
    }

    private static void rejectReparseComponents(Path path) throws SandboxException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new SandboxException("path has no Windows volume root: " + path);
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            int attributes = Kernel32.INSTANCE.GetFileAttributesW(new WString(current.toString()));
            if (attributes == INVALID_FILE_ATTRIBUTES) {
                throw win32("GetFileAttributesW(" + current + ")");
            }
            if ((attributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new SandboxException(
                        "Windows sandbox path contains a reparse point: " + current);
            }
        }
    }

    private static Pointer openWithoutDeleteSharing(Path path) throws SandboxException {
        return open(path, FILE_SHARE_READ | FILE_SHARE_WRITE);
    }

    private static Pointer openWithoutWriteOrDeleteSharing(Path path) throws SandboxException {
        return open(path, FILE_SHARE_READ);
    }

    private static Pointer open(Path path, int shareMode) throws SandboxException {
        Pointer handle =
                Kernel32.INSTANCE.CreateFileW(
                        new WString(path.toString()),
                        FILE_READ_ATTRIBUTES,
                        shareMode,
                        null,
                        OPEN_EXISTING,
                        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                        null);
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            throw win32("CreateFileW(path lease, " + path + ")");
        }
        return handle;
    }

    private static SandboxException win32(String operation) {
        return new SandboxException(operation + " failed, Win32=" + Native.getLastError());
    }

    private static void closeAll(List<Pointer> handles) {
        List<Pointer> reverse = new ArrayList<>(handles);
        Collections.reverse(reverse);
        reverse.forEach(Kernel32.INSTANCE::CloseHandle);
    }

    @Override
    public void close() {
        closeAll(handles);
        handles.clear();
    }
}
