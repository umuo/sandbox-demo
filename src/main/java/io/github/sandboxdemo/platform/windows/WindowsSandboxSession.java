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
import java.util.Comparator;
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
    private Pointer sealedRequestHandle;

    private WindowsSandboxSession(Path directory) {
        this.directory = directory;
        this.requestFile = directory.resolve("request.bin");
        this.resultFile = directory.resolve("result.bin");
    }

    static WindowsSandboxSession create(Path home, String sandboxUserSid)
            throws SandboxException, InterruptedException {
        Path sessions = home.resolve("sessions");
        try {
            Files.createDirectories(sessions);
            Path directory = Files.createDirectory(sessions.resolve("run-" + UUID.randomUUID()));
            WindowsSandboxSession session = new WindowsSandboxSession(directory);
            session.grantWorker(sandboxUserSid);
            return session;
        } catch (IOException e) {
            throw new SandboxException("failed to create Windows sandbox session directory", e);
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

    void sealRequest(String sandboxUserSid) throws SandboxException, InterruptedException {
        runIcacls(
                List.of(requestFile.toString(), "/deny", "*" + sandboxUserSid + ":(W,D)", "/Q"),
                "seal Windows worker request");
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
                    "failed to lock sealed Windows worker request, Win32=" + Native.getLastError());
        }
    }

    private void grantWorker(String sid) throws SandboxException, InterruptedException {
        runIcacls(
                List.of(directory.toString(), "/grant", "*" + sid + ":(OI)(CI)(M)", "/Q"),
                "grant Windows worker session access");
    }

    private static void runIcacls(List<String> arguments, String operation)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path icacls = Path.of(systemRoot, "System32", "icacls.exe");
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(icacls.toString());
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new SandboxException(
                        "failed to "
                                + operation
                                + " (exit="
                                + exitCode
                                + "): "
                                + new String(output, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new SandboxException("failed to launch icacls.exe to " + operation, e);
        }
    }

    @Override
    public void close() {
        if (sealedRequestHandle != null && Pointer.nativeValue(sealedRequestHandle) != -1L) {
            Kernel32.INSTANCE.CloseHandle(sealedRequestHandle);
            sealedRequestHandle = null;
        }
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                    // A later setup maintenance pass may remove abandoned sessions.
                                }
                            });
        } catch (IOException ignored) {
            // Best effort; no credential is stored in this directory.
        }
    }
}
