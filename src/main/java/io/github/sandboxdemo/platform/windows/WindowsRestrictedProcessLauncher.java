package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Advapi32;
import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Launches a suspended process under a write-restricted token and Job Object. */
final class WindowsRestrictedProcessLauncher {

    private static final int TOKEN_ASSIGN_PRIMARY = 0x0001;
    private static final int TOKEN_DUPLICATE = 0x0002;
    private static final int TOKEN_QUERY = 0x0008;
    private static final int TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private static final int TOKEN_ADJUST_DEFAULT = 0x0080;
    private static final int TOKEN_ADJUST_SESSIONID = 0x0100;

    private static final int DISABLE_MAX_PRIVILEGE = 0x00000001;
    private static final int LUA_TOKEN = 0x00000004;
    private static final int WRITE_RESTRICTED = 0x00000008;

    private static final int HANDLE_FLAG_INHERIT = 0x00000001;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int CREATE_SUSPENDED = 0x00000004;
    private static final int CREATE_UNICODE_ENVIRONMENT = 0x00000400;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002L;

    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int JOB_OBJECT_LIMIT_ACTIVE_PROCESS = 0x00000008;
    private static final int JOB_OBJECT_LIMIT_JOB_MEMORY = 0x00000200;
    private static final int DEFAULT_MAX_PROCESSES = 64;
    private static final long DEFAULT_MAX_MEMORY_MIB = 2048;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 258;
    private static final int ERROR_BROKEN_PIPE = 109;
    private static final int ERROR_NO_DATA = 232;

    private WindowsRestrictedProcessLauncher() {}

    static SandboxResult execute(
            Path executable,
            List<String> arguments,
            byte[] standardInput,
            ValidatedPolicy policy,
            Map<String, String> environment,
            List<String> capabilitySids)
            throws SandboxException, InterruptedException {

        long started = System.nanoTime();
        List<Pointer> convertedSids = new ArrayList<>();
        Pointer baseToken = null;
        Pointer restrictedToken = null;
        Pointer stdoutRead = null;
        Pointer stdoutWrite = null;
        Pointer stderrRead = null;
        Pointer stderrWrite = null;
        Pointer stdinRead = null;
        AtomicReference<Pointer> stdinWrite = new AtomicReference<>();
        HandleList attributeHandles = null;
        Pointer job = null;
        WindowsNative.PROCESS_INFORMATION processInfo = null;
        ExecutorService readers = null;
        WindowsPrivateDesktop desktop = null;

        try {
            baseToken = openCurrentToken();
            for (String sid : capabilitySids) {
                convertedSids.add(convertSid(sid));
            }
            restrictedToken = createRestrictedToken(baseToken, convertedSids);
            desktop = WindowsPrivateDesktop.create(currentUserSid(), capabilitySids);

            WindowsNative.SECURITY_ATTRIBUTES inheritable = inheritableAttributes();
            Pointer[] stdout = createPipe(inheritable);
            stdoutRead = stdout[0];
            stdoutWrite = stdout[1];
            Pointer[] stderr = createPipe(inheritable);
            stderrRead = stderr[0];
            stderrWrite = stderr[1];
            Pointer[] stdin = createInputPipe(inheritable);
            stdinRead = stdin[0];
            stdinWrite.set(stdin[1]);

            attributeHandles =
                    createHandleList(
                            io.github.sandboxdemo.core.Java8.listOf(
                                    stdinRead, stdoutWrite, stderrWrite));
            job = createKillOnCloseJob();

            WindowsNative.STARTUPINFOEX startup = new WindowsNative.STARTUPINFOEX();
            startup.StartupInfo.cb = startup.size();
            startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            startup.StartupInfo.hStdInput = stdinRead;
            startup.StartupInfo.hStdOutput = stdoutWrite;
            startup.StartupInfo.hStdError = stderrWrite;
            startup.StartupInfo.lpDesktop = desktop.startupName();
            startup.lpAttributeList = attributeHandles.attributeList();
            startup.write();

            String commandLine = WindowsCommandLine.build(executable.toString(), arguments);
            Memory commandLineMemory = WindowsNative.wideString(commandLine);
            Memory environmentBlock = environmentBlock(environment);
            processInfo = new WindowsNative.PROCESS_INFORMATION();

            boolean created =
                    Advapi32.INSTANCE.CreateProcessAsUserW(
                            restrictedToken,
                            new WString(executable.toString()),
                            commandLineMemory,
                            null,
                            null,
                            true,
                            CREATE_SUSPENDED
                                    | CREATE_UNICODE_ENVIRONMENT
                                    | EXTENDED_STARTUPINFO_PRESENT
                                    | CREATE_NO_WINDOW,
                            environmentBlock,
                            new WString(policy.workingDirectory().toString()),
                            startup,
                            processInfo);
            if (!created) {
                throw win32("CreateProcessAsUserW");
            }
            processInfo.read();

            if (!Kernel32.INSTANCE.AssignProcessToJobObject(job, processInfo.hProcess)) {
                int error = Native.getLastError();
                Kernel32.INSTANCE.TerminateJobObject(job, 125);
                throw win32("AssignProcessToJobObject", error);
            }
            if (Kernel32.INSTANCE.ResumeThread(processInfo.hThread) == -1) {
                int error = Native.getLastError();
                Kernel32.INSTANCE.TerminateJobObject(job, 125);
                throw win32("ResumeThread", error);
            }

            close(processInfo.hThread);
            processInfo.hThread = null;
            close(stdoutWrite);
            stdoutWrite = null;
            close(stderrWrite);
            stderrWrite = null;
            close(stdinRead);
            stdinRead = null;

            Pointer capturedStdout = stdoutRead;
            Pointer capturedStderr = stderrRead;
            readers =
                    Executors.newFixedThreadPool(
                            3,
                            runnable -> {
                                Thread thread =
                                        new Thread(runnable, "windows-sandbox-output-reader");
                                thread.setDaemon(true);
                                return thread;
                            });
            Future<CapturedOutput> stdoutTask =
                    readers.submit(() -> readPipe(capturedStdout, policy.maxOutputBytes()));
            Future<CapturedOutput> stderrTask =
                    readers.submit(() -> readPipe(capturedStderr, policy.maxOutputBytes()));
            Pointer capturedStdin = stdinWrite.get();
            Future<?> inputTask =
                    readers.submit(
                            () -> {
                                try {
                                    writePipe(capturedStdin, standardInput);
                                } finally {
                                    close(stdinWrite.getAndSet(null));
                                }
                                return null;
                            });

            int waitMillis = (int) Math.min(Integer.MAX_VALUE, policy.timeout().toMillis());
            int wait = Kernel32.INSTANCE.WaitForSingleObject(processInfo.hProcess, waitMillis);
            boolean timedOut = wait == WAIT_TIMEOUT;
            if (timedOut) {
                Kernel32.INSTANCE.TerminateJobObject(job, 124);
                Kernel32.INSTANCE.WaitForSingleObject(processInfo.hProcess, 5_000);
            } else if (wait != WAIT_OBJECT_0) {
                throw win32("WaitForSingleObject");
            }

            // Kill background descendants after the requested root command has exited.
            Kernel32.INSTANCE.TerminateJobObject(job, timedOut ? 124 : 0);
            awaitInput(inputTask);
            CapturedOutput out = await(stdoutTask);
            CapturedOutput err = await(stderrTask);
            IntByReference exitCode = new IntByReference(timedOut ? 124 : -1);
            if (!Kernel32.INSTANCE.GetExitCodeProcess(processInfo.hProcess, exitCode)) {
                throw win32("GetExitCodeProcess");
            }

            return new SandboxResult(
                    exitCode.getValue(),
                    timedOut,
                    out.bytes(),
                    err.bytes(),
                    out.truncated(),
                    err.truncated(),
                    Duration.ofNanos(System.nanoTime() - started));
        } finally {
            if (readers != null) {
                readers.shutdownNow();
            }
            if (job != null) {
                Kernel32.INSTANCE.TerminateJobObject(job, 125);
            }
            if (attributeHandles != null) {
                Kernel32.INSTANCE.DeleteProcThreadAttributeList(attributeHandles.attributeList());
            }
            close(processInfo == null ? null : processInfo.hThread);
            close(processInfo == null ? null : processInfo.hProcess);
            close(job);
            close(stdoutRead);
            close(stdoutWrite);
            close(stderrRead);
            close(stderrWrite);
            close(stdinRead);
            close(stdinWrite.getAndSet(null));
            close(restrictedToken);
            close(baseToken);
            convertedSids.forEach(Kernel32.INSTANCE::LocalFree);
            if (desktop != null) {
                desktop.close();
            }
        }
    }

    private static String currentUserSid() throws SandboxException {
        com.sun.jna.platform.win32.WinNT.HANDLEByReference token =
                new com.sun.jna.platform.win32.WinNT.HANDLEByReference();
        if (!com.sun.jna.platform.win32.Advapi32.INSTANCE.OpenProcessToken(
                com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess(),
                com.sun.jna.platform.win32.WinNT.TOKEN_QUERY,
                token)) {
            throw win32("OpenProcessToken(current SID)");
        }
        try {
            return com.sun.jna.platform.win32.Advapi32Util.getTokenAccount(token.getValue())
                    .sidString;
        } finally {
            com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(token.getValue());
        }
    }

    private static Pointer openCurrentToken() throws SandboxException {
        int access =
                TOKEN_ASSIGN_PRIMARY
                        | TOKEN_DUPLICATE
                        | TOKEN_QUERY
                        | TOKEN_ADJUST_PRIVILEGES
                        | TOKEN_ADJUST_DEFAULT
                        | TOKEN_ADJUST_SESSIONID;
        PointerByReference token = new PointerByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(
                Kernel32.INSTANCE.GetCurrentProcess(), access, token)) {
            throw win32("OpenProcessToken");
        }
        return token.getValue();
    }

    private static Pointer convertSid(String sid) throws SandboxException {
        PointerByReference converted = new PointerByReference();
        if (!Advapi32.INSTANCE.ConvertStringSidToSidW(new WString(sid), converted)) {
            throw win32("ConvertStringSidToSidW");
        }
        return converted.getValue();
    }

    private static Pointer createRestrictedToken(Pointer base, List<Pointer> sids)
            throws SandboxException {
        WindowsNative.SID_AND_ATTRIBUTES template = new WindowsNative.SID_AND_ATTRIBUTES();
        WindowsNative.SID_AND_ATTRIBUTES[] entries =
                (WindowsNative.SID_AND_ATTRIBUTES[]) template.toArray(sids.size());
        for (int i = 0; i < sids.size(); i++) {
            entries[i].Sid = sids.get(i);
            entries[i].Attributes = 0;
            entries[i].write();
        }

        PointerByReference restricted = new PointerByReference();
        int flags = DISABLE_MAX_PRIVILEGE | LUA_TOKEN | WRITE_RESTRICTED;
        if (!Advapi32.INSTANCE.CreateRestrictedToken(
                base,
                flags,
                0,
                null,
                0,
                null,
                entries.length,
                entries[0].getPointer(),
                restricted)) {
            throw win32("CreateRestrictedToken");
        }
        return restricted.getValue();
    }

    private static WindowsNative.SECURITY_ATTRIBUTES inheritableAttributes() {
        WindowsNative.SECURITY_ATTRIBUTES attributes = new WindowsNative.SECURITY_ATTRIBUTES();
        attributes.nLength = attributes.size();
        attributes.bInheritHandle = 1;
        attributes.write();
        return attributes;
    }

    private static Pointer[] createPipe(WindowsNative.SECURITY_ATTRIBUTES attributes)
            throws SandboxException {
        PointerByReference read = new PointerByReference();
        PointerByReference write = new PointerByReference();
        if (!Kernel32.INSTANCE.CreatePipe(read, write, attributes, 0)) {
            throw win32("CreatePipe");
        }
        if (!Kernel32.INSTANCE.SetHandleInformation(read.getValue(), HANDLE_FLAG_INHERIT, 0)) {
            int error = Native.getLastError();
            close(read.getValue());
            close(write.getValue());
            throw win32("SetHandleInformation", error);
        }
        return new Pointer[] {read.getValue(), write.getValue()};
    }

    private static Pointer[] createInputPipe(WindowsNative.SECURITY_ATTRIBUTES attributes)
            throws SandboxException {
        PointerByReference read = new PointerByReference();
        PointerByReference write = new PointerByReference();
        if (!Kernel32.INSTANCE.CreatePipe(read, write, attributes, 0)) {
            throw win32("CreatePipe(stdin)");
        }
        if (!Kernel32.INSTANCE.SetHandleInformation(write.getValue(), HANDLE_FLAG_INHERIT, 0)) {
            int error = Native.getLastError();
            close(read.getValue());
            close(write.getValue());
            throw win32("SetHandleInformation(stdin)", error);
        }
        return new Pointer[] {read.getValue(), write.getValue()};
    }

    private static HandleList createHandleList(List<Pointer> handles) throws SandboxException {
        WindowsNative.SIZE_TByReference size = new WindowsNative.SIZE_TByReference();
        Kernel32.INSTANCE.InitializeProcThreadAttributeList(null, 1, 0, size);
        if (size.getValue().longValue() == 0) {
            throw win32("InitializeProcThreadAttributeList(size)");
        }

        Memory list = new Memory(size.getValue().longValue());
        if (!Kernel32.INSTANCE.InitializeProcThreadAttributeList(list, 1, 0, size)) {
            throw win32("InitializeProcThreadAttributeList");
        }

        Memory handleArray = new Memory((long) handles.size() * Native.POINTER_SIZE);
        for (int i = 0; i < handles.size(); i++) {
            handleArray.setPointer((long) i * Native.POINTER_SIZE, handles.get(i));
        }
        if (!Kernel32.INSTANCE.UpdateProcThreadAttribute(
                list,
                0,
                new BaseTSD.ULONG_PTR(PROC_THREAD_ATTRIBUTE_HANDLE_LIST),
                handleArray,
                new BaseTSD.SIZE_T((long) handles.size() * Native.POINTER_SIZE),
                null,
                null)) {
            int error = Native.getLastError();
            Kernel32.INSTANCE.DeleteProcThreadAttributeList(list);
            throw win32("UpdateProcThreadAttribute(HANDLE_LIST)", error);
        }
        // Keep handleArray strongly reachable until CreateProcessAsUserW consumes
        // the attribute list; UpdateProcThreadAttribute stores its pointer.
        return new HandleList(list, handleArray);
    }

    private static Pointer createKillOnCloseJob() throws SandboxException {
        int maxProcesses =
                positiveEnvironmentInt("SANDBOX_WINDOWS_MAX_PROCESSES", DEFAULT_MAX_PROCESSES);
        long memoryMib =
                positiveEnvironmentLong("SANDBOX_WINDOWS_MAX_MEMORY_MIB", DEFAULT_MAX_MEMORY_MIB);
        long memoryBytes;
        try {
            memoryBytes = Math.multiplyExact(memoryMib, 1024L * 1024L);
        } catch (ArithmeticException e) {
            throw new SandboxException("SANDBOX_WINDOWS_MAX_MEMORY_MIB is too large", e);
        }
        Pointer job = Kernel32.INSTANCE.CreateJobObjectW(null, null);
        if (isInvalid(job)) {
            throw win32("CreateJobObjectW");
        }
        WindowsNative.JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits =
                new WindowsNative.JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        limits.BasicLimitInformation.LimitFlags =
                JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                        | JOB_OBJECT_LIMIT_ACTIVE_PROCESS
                        | JOB_OBJECT_LIMIT_JOB_MEMORY;
        limits.BasicLimitInformation.ActiveProcessLimit = maxProcesses;
        limits.JobMemoryLimit = new BaseTSD.SIZE_T(memoryBytes);
        limits.write();
        if (!Kernel32.INSTANCE.SetInformationJobObject(
                job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits.getPointer(), limits.size())) {
            int error = Native.getLastError();
            close(job);
            throw win32("SetInformationJobObject", error);
        }
        return job;
    }

    private static int positiveEnvironmentInt(String name, int defaultValue)
            throws SandboxException {
        long value = positiveEnvironmentLong(name, defaultValue);
        if (value > Integer.MAX_VALUE) {
            throw new SandboxException(name + " exceeds the supported integer range");
        }
        return (int) value;
    }

    private static long positiveEnvironmentLong(String name, long defaultValue)
            throws SandboxException {
        String configured = System.getenv(name);
        if (configured == null || io.github.sandboxdemo.core.Java8.isBlank(configured)) {
            return defaultValue;
        }
        try {
            long value = Long.parseLong(configured);
            if (value < 1) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new SandboxException(name + " must be a positive integer", e);
        }
    }

    private static Memory environmentBlock(Map<String, String> environment) {
        StringBuilder block = new StringBuilder();
        environment.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(String::toLowerCase)))
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

    private static void writePipe(Pointer pipe, byte[] input) throws SandboxException {
        int offset = 0;
        while (offset < input.length) {
            int length = Math.min(64 * 1024, input.length - offset);
            byte[] chunk = java.util.Arrays.copyOfRange(input, offset, offset + length);
            IntByReference written = new IntByReference();
            if (!Kernel32.INSTANCE.WriteFile(pipe, chunk, chunk.length, written, null)) {
                int error = Native.getLastError();
                if (error == ERROR_BROKEN_PIPE || error == ERROR_NO_DATA) {
                    return;
                }
                throw new SandboxException("WriteFile(stdin) failed, Win32=" + error);
            }
            if (written.getValue() < 1) {
                throw new SandboxException("WriteFile(stdin) made no progress");
            }
            offset += written.getValue();
        }
    }

    private static void awaitInput(Future<?> task) throws SandboxException, InterruptedException {
        try {
            task.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SandboxException) {
                throw (SandboxException) cause;
            }
            throw new SandboxException("failed to provide Windows sandbox stdin", cause);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new SandboxException("Windows sandbox stdin pipe did not close", e);
        }
    }

    private static CapturedOutput readPipe(Pointer pipe, int limit) throws SandboxException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        boolean truncated = false;
        while (true) {
            IntByReference read = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(pipe, buffer, buffer.length, read, null)) {
                int error = Native.getLastError();
                if (error == ERROR_BROKEN_PIPE) {
                    break;
                }
                throw new SandboxException("ReadFile(pipe) failed, Win32=" + error);
            }
            int count = read.getValue();
            if (count == 0) {
                break;
            }
            int remaining = limit - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(remaining, count));
            }
            if (count > remaining) {
                truncated = true;
            }
        }
        return new CapturedOutput(output.toByteArray(), truncated);
    }

    private static CapturedOutput await(Future<CapturedOutput> task)
            throws SandboxException, InterruptedException {
        try {
            return task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SandboxException) {
                throw (SandboxException) cause;
            }
            throw new SandboxException("failed to capture Windows sandbox output", cause);
        }
    }

    private static SandboxException win32(String operation) {
        return win32(operation, Native.getLastError());
    }

    private static SandboxException win32(String operation, int error) {
        return new SandboxException(operation + " failed, Win32=" + error);
    }

    private static boolean isInvalid(Pointer handle) {
        return handle == null || Pointer.nativeValue(handle) == -1L;
    }

    private static void close(Pointer handle) {
        if (!isInvalid(handle)) {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private static final class CapturedOutput {

        private final byte[] bytes;
        private final boolean truncated;

        private CapturedOutput(byte[] bytes, boolean truncated) {
            this.bytes = bytes;
            this.truncated = truncated;
        }

        byte[] bytes() {
            return bytes;
        }

        boolean truncated() {
            return truncated;
        }
    }

    private static final class HandleList {

        private final Pointer attributeList;

        @SuppressWarnings("unused")
        private final Memory handleArray;

        private HandleList(Pointer attributeList, Memory handleArray) {
            this.attributeList = attributeList;
            this.handleArray = handleArray;
        }

        Pointer attributeList() {
            return attributeList;
        }
    }
}
