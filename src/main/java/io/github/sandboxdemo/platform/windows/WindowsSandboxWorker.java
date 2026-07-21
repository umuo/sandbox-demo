package io.github.sandboxdemo.platform.windows;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxResult;
import java.nio.file.Path;

/** Internal entry point launched under a dedicated local sandbox account. */
public final class WindowsSandboxWorker {

    private WindowsSandboxWorker() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            System.exit(2);
        }
        Path requestFile = Path.of(args[0]);
        Path resultFile = Path.of(args[1]);
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
            try (WindowsPathLease ignored =
                    WindowsPathLease.acquire(request.policy(), request.executable())) {
                SandboxResult result =
                        WindowsRestrictedProcessLauncher.execute(
                                request.executable(),
                                request.arguments(),
                                request.standardInput(),
                                request.policy(),
                                request.environment(),
                                request.capabilitySids());
                WindowsWorkerProtocol.writeResult(resultFile, result, null);
            }
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
        if (message == null || message.isBlank()) {
            return "no error message";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
