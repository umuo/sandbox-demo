package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Advapi32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Starts the trusted Java worker under a dedicated Windows local account. */
final class WindowsWorkerLauncher {

    private static final int LOGON_WITH_PROFILE = 0x00000001;
    private static final int CREATE_SUSPENDED = 0x00000004;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int WORKER_GRACE_MILLIS = 15_000;

    private WindowsWorkerLauncher() {}

    static SandboxResult execute(
            WindowsSandboxInstallation.Credential credential,
            WindowsWorkerRuntime runtime,
            WindowsSandboxSession session,
            ValidatedPolicy policy)
            throws SandboxException, InterruptedException {

        List<String> workerArguments =
                io.github.sandboxdemo.core.Java8.listOf(
                        "-cp",
                        runtime.classPathArgument(),
                        WindowsSandboxWorker.class.getName(),
                        session.requestFile().toString(),
                        session.resultFile().toString());
        String commandLine =
                WindowsCommandLine.build(runtime.javaExecutable().toString(), workerArguments);
        Memory commandLineMemory = WindowsNative.wideString(commandLine);
        Memory environment = environmentBlock(workerEnvironment(session, runtime));
        WindowsNative.STARTUPINFO startup = new WindowsNative.STARTUPINFO();
        startup.cb = startup.size();
        startup.write();
        WindowsNative.PROCESS_INFORMATION process = new WindowsNative.PROCESS_INFORMATION();
        Pointer job = createKillOnCloseJob();

        try {
            boolean created =
                    Advapi32.INSTANCE.CreateProcessWithLogonW(
                            new WString(credential.username()),
                            new WString("."),
                            new WString(credential.password()),
                            LOGON_WITH_PROFILE,
                            new WString(runtime.javaExecutable().toString()),
                            commandLineMemory,
                            CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                            environment,
                            new WString(session.directory().toString()),
                            startup,
                            process);
            if (!created) {
                int error = Native.getLastError();
                if (error == 1385) {
                    throw new SandboxException(
                            "CreateProcessWithLogonW(worker) failed, Win32=1385: Windows has not "
                                    + "granted 'Log on locally' to the sandbox account "
                                    + credential.username()
                                    + "; rerun setup-windows from an elevated terminal or grant "
                                    + "SeInteractiveLogonRight and remove any applicable 'Deny log on locally' policy");
                }
                throw win32("CreateProcessWithLogonW(worker)", error);
            }
            process.read();
            if (!Kernel32.INSTANCE.AssignProcessToJobObject(job, process.hProcess)) {
                int error = Native.getLastError();
                Kernel32.INSTANCE.TerminateJobObject(job, 125);
                throw win32("AssignProcessToJobObject(worker)", error);
            }
            if (Kernel32.INSTANCE.ResumeThread(process.hThread) == -1) {
                int error = Native.getLastError();
                Kernel32.INSTANCE.TerminateJobObject(job, 125);
                throw win32("ResumeThread(worker)", error);
            }
            close(process.hThread);
            process.hThread = null;

            long waitMillis =
                    Math.min(Integer.MAX_VALUE, policy.timeout().toMillis() + WORKER_GRACE_MILLIS);
            int wait = Kernel32.INSTANCE.WaitForSingleObject(process.hProcess, (int) waitMillis);
            if (wait == WAIT_TIMEOUT) {
                Kernel32.INSTANCE.TerminateJobObject(job, 124);
                throw new SandboxException(
                        "Windows sandbox worker exceeded its shutdown grace period");
            }
            if (wait != WAIT_OBJECT_0) {
                throw win32("WaitForSingleObject(worker)");
            }

            IntByReference exitCode = new IntByReference();
            if (!Kernel32.INSTANCE.GetExitCodeProcess(process.hProcess, exitCode)) {
                throw win32("GetExitCodeProcess(worker)");
            }
            if (!java.nio.file.Files.isRegularFile(session.resultFile())) {
                throw new SandboxException(
                        "Windows sandbox worker exited "
                                + exitCode.getValue()
                                + " without a result file");
            }
            return WindowsWorkerProtocol.readResult(session.resultFile());
        } finally {
            if (job != null) {
                Kernel32.INSTANCE.TerminateJobObject(job, 125);
            }
            close(process.hThread);
            close(process.hProcess);
            close(job);
        }
    }

    private static Map<String, String> workerEnvironment(
            WindowsSandboxSession session, WindowsWorkerRuntime runtime) {
        Map<String, String> values = new LinkedHashMap<>();
        copy(values, "SystemRoot");
        copy(values, "WINDIR");
        copy(values, "SANDBOX_WINDOWS_MAX_PROCESSES");
        copy(values, "SANDBOX_WINDOWS_MAX_MEMORY_MIB");
        values.put("JAVA_HOME", runtime.javaExecutable().getParent().getParent().toString());
        values.put("TEMP", session.directory().toString());
        values.put("TMP", session.directory().toString());
        return values;
    }

    private static void copy(Map<String, String> target, String name) {
        String value = System.getenv(name);
        if (value != null && !io.github.sandboxdemo.core.Java8.isBlank(value)) {
            target.put(name, value);
        }
    }

    private static Pointer createKillOnCloseJob() throws SandboxException {
        Pointer job = Kernel32.INSTANCE.CreateJobObjectW(null, null);
        if (job == null || Pointer.nativeValue(job) == -1L) {
            throw win32("CreateJobObjectW(worker)");
        }
        WindowsNative.JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits =
                new WindowsNative.JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        limits.write();
        if (!Kernel32.INSTANCE.SetInformationJobObject(
                job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits.getPointer(), limits.size())) {
            int error = Native.getLastError();
            close(job);
            throw win32("SetInformationJobObject(worker)", error);
        }
        return job;
    }

    private static Memory environmentBlock(Map<String, String> environment) {
        StringBuilder block = new StringBuilder();
        environment.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(
                        entry ->
                                block.append(entry.getKey())
                                        .append('=')
                                        .append(entry.getValue())
                                        .append('\0'));
        block.append('\0');
        byte[] encoded = block.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory memory = new Memory(encoded.length);
        memory.write(0, encoded, 0, encoded.length);
        return memory;
    }

    private static SandboxException win32(String operation) {
        return win32(operation, Native.getLastError());
    }

    private static SandboxException win32(String operation, int error) {
        return new SandboxException(operation + " failed, Win32=" + error);
    }

    private static void close(Pointer handle) {
        if (handle != null && Pointer.nativeValue(handle) != -1L) {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }
}
