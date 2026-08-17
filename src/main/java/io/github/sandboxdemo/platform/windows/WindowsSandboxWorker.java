package io.github.sandboxdemo.platform.windows;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/** Internal entry point launched under a dedicated local sandbox account. */
public final class WindowsSandboxWorker {

    private WindowsSandboxWorker() {}

    public static void main(String[] args) {
        if (args.length != 5 || !("true".equals(args[4]) || "false".equals(args[4]))) {
            System.exit(2);
        }
        Path requestFile = java.nio.file.Paths.get(args[0]);
        Path resultFile = java.nio.file.Paths.get(args[1]);
        Path stdoutStreamFile = java.nio.file.Paths.get(args[2]);
        Path stderrStreamFile = java.nio.file.Paths.get(args[3]);
        boolean streamOutput = Boolean.parseBoolean(args[4]);
        try {
            WindowsWorkerProtocol.WorkerRequest request =
                    WindowsWorkerProtocol.readRequest(requestFile);
            String actualSid = currentUserSid();
            if (!actualSid.equalsIgnoreCase(request.expectedUserSid())) {
                throw new SandboxException(
                        "worker identity mismatch: expected "
                                + request.expectedUserSid()
                                + " but was "
                                + actualSid);
            }
            // The trusted host validates these paths and keeps its WindowsPathLease open until
            // this worker exits. Repeating that traversal here is both redundant and incorrect:
            // the dedicated account intentionally cannot inspect owner-private ancestors such as
            // C:\Users\<host>\AppData, even when an explicitly granted child is usable.
            SandboxResult result;
            if (streamOutput) {
                StreamFileConsumer stdoutStream = new StreamFileConsumer(stdoutStreamFile);
                StreamFileConsumer stderrStream = new StreamFileConsumer(stderrStreamFile);
                result =
                        WindowsRestrictedProcessLauncher.execute(
                                request.executable(),
                                request.arguments(),
                                request.standardInput(),
                                request.policy(),
                                request.environment(),
                                request.capabilitySids(),
                                true,
                                stdoutStream,
                                stderrStream);
                stdoutStream.throwIfFailed();
                stderrStream.throwIfFailed();
            } else {
                result =
                        WindowsRestrictedProcessLauncher.execute(
                                request.executable(),
                                request.arguments(),
                                request.standardInput(),
                                request.policy(),
                                request.environment(),
                                request.capabilitySids(),
                                true);
            }
            WindowsWorkerProtocol.writeResult(resultFile, result, null);
        } catch (Throwable error) {
            try {
                WindowsWorkerProtocol.writeResult(
                        resultFile,
                        null,
                        error.getClass().getSimpleName() + ": " + safeMessage(error));
            } catch (Exception ignored) {
                // The host reports a missing result if even this fail-closed path fails.
            }
            System.exit(125);
        }
    }

    static final class StreamFileConsumer implements Consumer<byte[]> {

        private final Path chunkFile;
        private final Path temporaryFile;
        private IOException failure;

        StreamFileConsumer(Path chunkFile) {
            this.chunkFile = chunkFile;
            this.temporaryFile = chunkFile.resolveSibling(chunkFile.getFileName() + ".tmp");
        }

        @Override
        public synchronized void accept(byte[] chunk) {
            if (failure != null) {
                return;
            }
            try {
                Files.write(temporaryFile, chunk);
                Files.move(temporaryFile, chunkFile, StandardCopyOption.ATOMIC_MOVE);
                while (Files.exists(chunkFile)) {
                    Thread.sleep(2);
                }
            } catch (IOException e) {
                failure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = new IOException("interrupted while streaming Windows worker output", e);
            }
        }

        synchronized void throwIfFailed() throws IOException {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static String currentUserSid() throws SandboxException {
        WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(
                Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new SandboxException(
                    "OpenProcessToken(worker) failed, Win32=" + Kernel32.INSTANCE.GetLastError());
        }
        try {
            return Advapi32Util.getTokenAccount(token.getValue()).sidString;
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.getValue());
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || io.github.sandboxdemo.core.Java8.isBlank(message)) {
            return "no error message";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
