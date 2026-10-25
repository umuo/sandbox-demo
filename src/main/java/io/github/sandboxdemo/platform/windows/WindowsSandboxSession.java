package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import io.github.sandboxdemo.api.SandboxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Host/worker exchange directory with an explicit sandbox-user DACL. */
final class WindowsSandboxSession implements AutoCloseable {

    private static final int GENERIC_READ = 0x80000000;
    private static final int FILE_SHARE_READ = 0x00000001;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_ATTRIBUTE_NORMAL = 0x00000080;

    private final Path directory;
    private final Path requestFile;
    private final Path resultFile;
    private final Path stdoutStreamFile;
    private final Path stderrStreamFile;
    private Pointer sealedRequestHandle;
    private final WindowsCleanup.Check cleanup =
            WindowsCleanup.begin(io.github.sandboxdemo.api.SandboxHostResource.WORKER_SESSION);
    private boolean closed;

    private WindowsSandboxSession(Path directory) {
        this.directory = directory;
        this.requestFile = directory.resolve("request.bin");
        this.resultFile = directory.resolve("result.bin");
        this.stdoutStreamFile = directory.resolve("stdout.stream");
        this.stderrStreamFile = directory.resolve("stderr.stream");
    }

    static WindowsSandboxSession create(Path home, String sandboxUserSid)
            throws SandboxException, InterruptedException {
        Path sessions = home.resolve("sessions");
        try {
            Files.createDirectories(sessions);
            Path directory = Files.createDirectory(sessions.resolve("run-" + UUID.randomUUID()));
            WindowsSandboxSession session = new WindowsSandboxSession(directory);
            try {
                session.grantWorker(sandboxUserSid);
            } catch (SandboxException | InterruptedException | RuntimeException failure) {
                session.close();
                throw failure;
            }
            return session;
        } catch (IOException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "failed to create Windows sandbox session directory",
                    e);
        }
    }

    Path directory() {
        return directory;
    }

    Path requestFile() {
        return requestFile;
    }

    Path resultFile() {
        return resultFile;
    }

    Path stdoutStreamFile() {
        return stdoutStreamFile;
    }

    Path stderrStreamFile() {
        return stderrStreamFile;
    }

    void sealRequest(String sandboxUserSid) throws SandboxException {
        // Do not use icacls `(W)` here. FILE_GENERIC_WRITE also contains READ_CONTROL and
        // SYNCHRONIZE; denying the generic mask can prevent the worker from reading its request.
        // The native ACL helper denies only the concrete mutation rights and leaves read access
        // intact. The host-owned handle below additionally prevents write/delete sharing while
        // the worker is alive.
        WindowsAclManager.denyFileWrites(requestFile, sandboxUserSid);
        sealedRequestHandle =
                Kernel32.INSTANCE.CreateFileW(
                        new WString(requestFile.toString()),
                        GENERIC_READ,
                        FILE_SHARE_READ,
                        null,
                        OPEN_EXISTING,
                        FILE_ATTRIBUTE_NORMAL,
                        null);
        if (sealedRequestHandle == null || Pointer.nativeValue(sealedRequestHandle) == -1L) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "failed to lock sealed Windows worker request, Win32=" + Native.getLastError());
        }
    }

    private void grantWorker(String sid) throws SandboxException, InterruptedException {
        runIcacls(
                io.github.sandboxdemo.core.Java8.listOf(
                        directory.toString(), "/grant", "*" + sid + ":(OI)(CI)(M)", "/Q"),
                "grant Windows worker session access");
    }

    private static void runIcacls(List<String> arguments, String operation)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path icacls = java.nio.file.Paths.get(systemRoot, "System32", "icacls.exe");
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(icacls.toString());
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = io.github.sandboxdemo.core.Java8.readAllBytes(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                        io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                        "failed to "
                                + operation
                                + " (exit="
                                + exitCode
                                + "): "
                                + new String(output, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "failed to launch icacls.exe to " + operation,
                    e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        boolean success = true;
        if (sealedRequestHandle != null && Pointer.nativeValue(sealedRequestHandle) != -1L) {
            success = WindowsCleanup.closeHandle(sealedRequestHandle);
            sealedRequestHandle = null;
        }
        success &=
                WindowsCleanup.run(
                        io.github.sandboxdemo.api.SandboxHostResource.WORKER_SESSION,
                        () -> io.github.sandboxdemo.core.PrivateDirectories.removeTree(directory));
        cleanup.complete(
                success
                        ? io.github.sandboxdemo.api.SandboxCleanupState.CONFIRMED
                        : io.github.sandboxdemo.api.SandboxCleanupState.FAILED);
    }
}
