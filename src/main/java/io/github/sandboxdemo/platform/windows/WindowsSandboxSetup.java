package io.github.sandboxdemo.platform.windows;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.LMAccess;
import com.sun.jna.platform.win32.Netapi32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.core.OperatingSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/** Elevated install/uninstall lifecycle for dedicated Windows sandbox users. */
public final class WindowsSandboxSetup {

    private static final int NERR_SUCCESS = 0;
    private static final int NERR_USER_EXISTS = 2224;
    private static final int USER_PRIV_USER = 1;
    private static final int UF_SCRIPT = 0x0001;
    private static final int UF_PASSWD_CANT_CHANGE = 0x0040;
    private static final int UF_DONT_EXPIRE_PASSWD = 0x10000;
    private static final int POLICY_LOOKUP_NAMES = 0x00000800;
    private static final int POLICY_CREATE_ACCOUNT = 0x00000010;
    private static final int LOGON32_LOGON_INTERACTIVE = 2;
    private static final int LOGON32_PROVIDER_DEFAULT = 0;
    private static final String LOGON_LOCALLY_RIGHT = "SeInteractiveLogonRight";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String USER_LIST_KEY =
            "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon\\SpecialAccounts\\UserList";

    private WindowsSandboxSetup() {}

    public static Path defaultHome() {
        return WindowsSandboxInstallation.defaultHome();
    }

    /** Returns whether installation metadata exists at the configured home. */
    public static boolean isInstalled(Path home) {
        return Files.isRegularFile(
                home.toAbsolutePath().normalize().resolve("windows-sandbox.properties"));
    }

    /**
     * Verifies an existing installation without changing users, ACLs, metadata, or firewall rules.
     */
    public static void verify(Path home) throws SandboxException, InterruptedException {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            throw new SandboxException("Windows sandbox verification can run only on Windows");
        }
        WindowsSandboxInstallation installation =
                WindowsSandboxInstallation.load(home.toAbsolutePath().normalize());
        installation.verifyAccounts();
        verifyInteractiveLogon(installation.offline());
        verifyInteractiveLogon(installation.online());
        WindowsFirewall.verify(installation.offline().sid());
        WindowsWorkerRuntime.verifyInstalled(installation);
    }

    public static void install(Path home) throws SandboxException, InterruptedException {
        requireWindowsAndElevation();
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path marker = normalizedHome.resolve("windows-sandbox.properties");
        validateInstallationHome(normalizedHome, marker);
        if (Files.exists(marker)) {
            WindowsSandboxInstallation existing = WindowsSandboxInstallation.load(normalizedHome);
            existing.verifyAccounts();
            grantLogonLocally(existing.offline());
            grantLogonLocally(existing.online());
            verifyInteractiveLogon(existing.offline());
            verifyInteractiveLogon(existing.online());
            try {
                cleanupKnownTree(normalizedHome.resolve("sessions"));
            } catch (IOException e) {
                throw new SandboxException("failed to clean abandoned Windows sessions", e);
            }
            installWorkerRuntime(existing);
            hideSandboxUsers();
            WindowsFirewall.install(existing.offline().sid());
            protectInstallationDirectory(normalizedHome);
            return;
        }

        String offlinePassword = newPassword();
        String onlinePassword = newPassword();
        boolean offlineCreated = false;
        boolean onlineCreated = false;
        WindowsSandboxInstallation installation = null;
        try {
            createUser(WindowsSandboxInstallation.DEFAULT_OFFLINE_USER, offlinePassword);
            offlineCreated = true;
            createUser(WindowsSandboxInstallation.DEFAULT_ONLINE_USER, onlinePassword);
            onlineCreated = true;

            String offlineSid = accountSid(WindowsSandboxInstallation.DEFAULT_OFFLINE_USER);
            String onlineSid = accountSid(WindowsSandboxInstallation.DEFAULT_ONLINE_USER);
            WindowsSandboxInstallation.Credential offline =
                    new WindowsSandboxInstallation.Credential(
                            WindowsSandboxInstallation.DEFAULT_OFFLINE_USER,
                            offlineSid,
                            offlinePassword);
            WindowsSandboxInstallation.Credential online =
                    new WindowsSandboxInstallation.Credential(
                            WindowsSandboxInstallation.DEFAULT_ONLINE_USER,
                            onlineSid,
                            onlinePassword);
            grantLogonLocally(offline);
            grantLogonLocally(online);
            verifyInteractiveLogon(offline);
            verifyInteractiveLogon(online);
            Files.createDirectories(normalizedHome);
            protectInstallationDirectory(normalizedHome);
            installation =
                    new WindowsSandboxInstallation(
                            normalizedHome,
                            java.nio.file.Paths.get(System.getProperty("java.home")),
                            offline,
                            online);
            installation.save();
            installWorkerRuntime(installation);
            hideSandboxUsers();
            protectInstallationDirectory(normalizedHome);
            WindowsFirewall.install(offlineSid);
        } catch (IOException | SandboxException | InterruptedException e) {
            try {
                WindowsFirewall.uninstall();
            } catch (Exception ignored) {
                // Continue rolling back local users and metadata.
            }
            if (installation != null) {
                try {
                    removeRuntimeAccess(installation);
                    cleanupKnownTree(normalizedHome.resolve("runtime"));
                } catch (Exception ignored) {
                    // Deleted account SIDs are inert if ACL cleanup cannot complete.
                }
            }
            unhideSandboxUsers();
            if (onlineCreated) {
                Netapi32.INSTANCE.NetUserDel(null, WindowsSandboxInstallation.DEFAULT_ONLINE_USER);
            }
            if (offlineCreated) {
                Netapi32.INSTANCE.NetUserDel(null, WindowsSandboxInstallation.DEFAULT_OFFLINE_USER);
            }
            try {
                Files.deleteIfExists(marker);
            } catch (IOException ignored) {
                // The original setup error is more actionable.
            }
            if (e instanceof InterruptedException) {
                throw (InterruptedException) e;
            }
            if (e instanceof SandboxException) {
                throw (SandboxException) e;
            }
            throw new SandboxException("failed to install the Windows sandbox", e);
        }
    }

    public static void uninstall(Path home) throws SandboxException, InterruptedException {
        requireWindowsAndElevation();
        Path normalizedHome = home.toAbsolutePath().normalize();
        Path marker = normalizedHome.resolve("windows-sandbox.properties");
        if (!Files.isRegularFile(marker)) {
            throw new SandboxException(
                    "refusing to uninstall without matching installation metadata: " + marker);
        }
        WindowsSandboxInstallation installation = WindowsSandboxInstallation.load(normalizedHome);
        WindowsFirewall.uninstall();
        cleanupWorkspaceAccess(installation);
        removeRuntimeAccess(installation);
        unhideSandboxUsers();
        deleteUserIfPresent(WindowsSandboxInstallation.DEFAULT_OFFLINE_USER);
        deleteUserIfPresent(WindowsSandboxInstallation.DEFAULT_ONLINE_USER);
        try {
            Files.deleteIfExists(marker);
            cleanupKnownTree(normalizedHome.resolve("sessions"));
            cleanupKnownTree(normalizedHome.resolve("runtime"));
            Files.deleteIfExists(normalizedHome.resolve("workspace-acls.tsv"));
            try {
                Files.deleteIfExists(normalizedHome);
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                // Never remove caller-owned files from a configured home.
            }
        } catch (IOException e) {
            throw new SandboxException("failed to remove Windows sandbox metadata: " + marker, e);
        }
    }

    private static void validateInstallationHome(Path home, Path marker) throws SandboxException {
        if (home.getParent() == null) {
            throw new SandboxException("Windows sandbox home must not be a volume root: " + home);
        }
        try {
            if (Files.exists(home)) {
                Path real = home.toRealPath();
                if (!real.toString().equalsIgnoreCase(home.toString())) {
                    throw new SandboxException(
                            "Windows sandbox home must not traverse a link or junction: " + home);
                }
                if (!Files.isDirectory(home)) {
                    throw new SandboxException("Windows sandbox home is not a directory: " + home);
                }
                if (!Files.exists(marker)) {
                    try (java.util.stream.Stream<Path> entries = Files.list(home)) {
                        if (entries.findAny().isPresent()) {
                            throw new SandboxException(
                                    "refusing to take ownership of a non-empty directory without "
                                            + "sandbox metadata: "
                                            + home);
                        }
                    }
                }
            } else {
                Path parent = home.getParent().toRealPath();
                if (!parent.toString().equalsIgnoreCase(home.getParent().toString())) {
                    throw new SandboxException(
                            "Windows sandbox home parent must not traverse a link or junction: "
                                    + home.getParent());
                }
            }
        } catch (IOException e) {
            throw new SandboxException("failed to validate Windows sandbox home: " + home, e);
        }
    }

    private static void cleanupKnownTree(Path directory) throws IOException {
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            for (Path path :
                    paths.sorted(java.util.Comparator.reverseOrder())
                            .collect(java.util.stream.Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void requireWindowsAndElevation() throws SandboxException {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            throw new SandboxException("Windows sandbox setup can run only on Windows");
        }
        if (!Advapi32Util.isCurrentProcessElevated()) {
            throw new SandboxException(
                    "Windows sandbox setup requires an elevated Administrator terminal");
        }
    }

    private static void createUser(String username, String password) throws SandboxException {
        LMAccess.USER_INFO_1 user = new LMAccess.USER_INFO_1();
        user.usri1_name = username;
        user.usri1_password = password;
        user.usri1_priv = USER_PRIV_USER;
        user.usri1_comment = "Dedicated account for cross-platform Java sandbox";
        user.usri1_flags = UF_SCRIPT | UF_PASSWD_CANT_CHANGE | UF_DONT_EXPIRE_PASSWD;
        user.write();
        IntByReference parameterError = new IntByReference();
        int result = Netapi32.INSTANCE.NetUserAdd(null, 1, user, parameterError);
        if (result == NERR_USER_EXISTS) {
            throw new SandboxException(
                    "local sandbox user already exists without matching installation metadata: "
                            + username
                            + "; remove it explicitly before setup");
        }
        if (result != NERR_SUCCESS) {
            throw new SandboxException(
                    "NetUserAdd("
                            + username
                            + ") failed, status="
                            + result
                            + ", parameter="
                            + parameterError.getValue());
        }
    }

    private static void deleteUserIfPresent(String username) throws SandboxException {
        int result = Netapi32.INSTANCE.NetUserDel(null, username);
        if (result != NERR_SUCCESS && result != 2221) {
            throw new SandboxException("NetUserDel(" + username + ") failed, status=" + result);
        }
    }

    private static String accountSid(String username) throws SandboxException {
        try {
            return Advapi32Util.getAccountByName(null, username).sidString;
        } catch (RuntimeException e) {
            throw new SandboxException("failed to resolve SID for local user: " + username, e);
        }
    }

    /**
     * CreateProcessWithLogonW uses an interactive logon token. Do not rely on the machine's
     * membership/default policy for the dedicated accounts: domain security baselines commonly
     * remove the Users group's local-logon right, which otherwise surfaces only as Win32=1385 at
     * execution time.
     */
    private static void grantLogonLocally(WindowsSandboxInstallation.Credential credential)
            throws SandboxException {
        PointerByReference sid = new PointerByReference();
        if (!WindowsNative.Advapi32.INSTANCE.ConvertStringSidToSidW(
                new com.sun.jna.WString(credential.sid()), sid)) {
            throw new SandboxException(
                    "ConvertStringSidToSidW("
                            + credential.username()
                            + ") failed, Win32="
                            + Native.getLastError());
        }

        PointerByReference policy = new PointerByReference();
        try {
            WindowsNative.LSA_OBJECT_ATTRIBUTES attributes =
                    new WindowsNative.LSA_OBJECT_ATTRIBUTES();
            int status =
                    WindowsNative.Advapi32.INSTANCE.LsaOpenPolicy(
                            null, attributes, POLICY_LOOKUP_NAMES | POLICY_CREATE_ACCOUNT, policy);
            checkLsaStatus(status, "LsaOpenPolicy for " + credential.username());

            Memory rightMemory = WindowsNative.wideString(LOGON_LOCALLY_RIGHT);
            WindowsNative.LSA_UNICODE_STRING right =
                    new WindowsNative.LSA_UNICODE_STRING(
                            rightMemory, LOGON_LOCALLY_RIGHT.length() * Native.WCHAR_SIZE);
            right.write();
            status =
                    WindowsNative.Advapi32.INSTANCE.LsaAddAccountRights(
                            policy.getValue(),
                            sid.getValue(),
                            new WindowsNative.LSA_UNICODE_STRING[] {right},
                            1);
            checkLsaStatus(status, "LsaAddAccountRights(" + credential.username() + ")");
        } finally {
            if (policy.getValue() != null) {
                WindowsNative.Advapi32.INSTANCE.LsaClose(policy.getValue());
            }
            if (sid.getValue() != null) {
                WindowsNative.Kernel32.INSTANCE.LocalFree(sid.getValue());
            }
        }
    }

    private static void checkLsaStatus(int status, String operation) throws SandboxException {
        if (status != 0) {
            int win32 = WindowsNative.Advapi32.INSTANCE.LsaNtStatusToWinError(status);
            throw new SandboxException(operation + " failed, Win32=" + win32);
        }
    }

    private static void verifyInteractiveLogon(WindowsSandboxInstallation.Credential credential)
            throws SandboxException {
        WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.LogonUser(
                credential.username(),
                ".",
                credential.password(),
                LOGON32_LOGON_INTERACTIVE,
                LOGON32_PROVIDER_DEFAULT,
                token)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == 1385) {
                throw new SandboxException(
                        "interactive logon verification failed for "
                                + credential.username()
                                + ", Win32=1385: grant 'Log on locally' and remove any applicable "
                                + "'Deny log on locally' local or domain policy");
            }
            throw new SandboxException(
                    "interactive logon verification failed for "
                            + credential.username()
                            + ", Win32="
                            + error);
        }
        Kernel32.INSTANCE.CloseHandle(token.getValue());
    }

    private static String currentUserSid() throws SandboxException {
        WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(
                Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new SandboxException(
                    "OpenProcessToken(setup) failed, Win32=" + Kernel32.INSTANCE.GetLastError());
        }
        try {
            return Advapi32Util.getTokenAccount(token.getValue()).sidString;
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.getValue());
        }
    }

    private static void protectInstallationDirectory(Path home)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        Path icacls = java.nio.file.Paths.get(systemRoot, "System32", "icacls.exe");
        List<String> command =
                io.github.sandboxdemo.core.Java8.listOf(
                        icacls.toString(),
                        home.toString(),
                        "/inheritance:r",
                        "/grant:r",
                        "*" + currentUserSid() + ":(OI)(CI)(F)",
                        "*S-1-5-18:(OI)(CI)(F)",
                        "*S-1-5-32-544:(OI)(CI)(F)",
                        "/Q");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = io.github.sandboxdemo.core.Java8.readAllBytes(process.getInputStream());
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new SandboxException(
                        "failed to protect Windows sandbox metadata (exit="
                                + exitCode
                                + "): "
                                + new String(output, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new SandboxException("failed to launch icacls.exe for sandbox metadata", e);
        }
    }

    private static String newPassword() {
        byte[] random = new byte[36];
        RANDOM.nextBytes(random);
        return "Sbx!" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static void hideSandboxUsers() throws SandboxException {
        try {
            Advapi32Util.registryCreateKey(WinReg.HKEY_LOCAL_MACHINE, USER_LIST_KEY);
            Advapi32Util.registrySetIntValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    USER_LIST_KEY,
                    WindowsSandboxInstallation.DEFAULT_OFFLINE_USER,
                    0);
            Advapi32Util.registrySetIntValue(
                    WinReg.HKEY_LOCAL_MACHINE,
                    USER_LIST_KEY,
                    WindowsSandboxInstallation.DEFAULT_ONLINE_USER,
                    0);
        } catch (RuntimeException e) {
            throw new SandboxException("failed to hide Windows sandbox users from sign-in UI", e);
        }
    }

    private static void unhideSandboxUsers() {
        for (String username :
                io.github.sandboxdemo.core.Java8.listOf(
                        WindowsSandboxInstallation.DEFAULT_OFFLINE_USER,
                        WindowsSandboxInstallation.DEFAULT_ONLINE_USER)) {
            try {
                Advapi32Util.registryDeleteValue(
                        WinReg.HKEY_LOCAL_MACHINE, USER_LIST_KEY, username);
            } catch (RuntimeException ignored) {
                // Value can legitimately be absent.
            }
        }
    }

    private static void installWorkerRuntime(WindowsSandboxInstallation installation)
            throws SandboxException, InterruptedException {
        List<Path> sources = WindowsWorkerRuntime.sourceRuntimeJars();
        Path runtimeDirectory = WindowsWorkerRuntime.runtimeDirectory(installation.home());
        List<String> installedNames = new java.util.ArrayList<>(sources.size());
        try {
            Files.createDirectories(runtimeDirectory);
            int index = 0;
            for (Path source : sources) {
                String digest = WindowsWorkerRuntime.sha256Hex(source);
                String name = "sandbox-runtime-" + index++ + "-" + digest.substring(0, 16) + ".jar";
                Path target = runtimeDirectory.resolve(name);
                Path temporary = Files.createTempFile(runtimeDirectory, "sandbox-runtime-", ".tmp");
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                moveAtomically(temporary, target);
                WindowsWorkerRuntime.writeChecksum(target);
                installedNames.add(name);
            }

            Path manifest = WindowsWorkerRuntime.classPathManifest(installation.home());
            Path manifestTemporary =
                    Files.createTempFile(runtimeDirectory, "worker-classpath-", ".tmp");
            Files.write(
                    manifestTemporary, installedNames, java.nio.charset.StandardCharsets.US_ASCII);
            moveAtomically(manifestTemporary, manifest);
            cleanupObsoleteRuntimeFiles(runtimeDirectory, installedNames);
        } catch (IOException e) {
            throw new SandboxException("failed to install the Windows sandbox worker JARs", e);
        }
        WindowsWorkerRuntime.verifyInstalled(installation);
        grantRuntimeRead(
                runtimeDirectory, installation.offline().sid(), installation.online().sid());
        grantRuntimeRead(
                installation.javaHome(), installation.offline().sid(), installation.online().sid());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            try {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private static void cleanupObsoleteRuntimeFiles(Path runtimeDirectory, List<String> activeNames)
            throws IOException {
        java.util.Set<String> keep = new java.util.HashSet<>(activeNames);
        activeNames.forEach(name -> keep.add(name + ".sha256"));
        keep.add(
                WindowsWorkerRuntime.classPathManifest(runtimeDirectory.getParent())
                        .getFileName()
                        .toString());
        try (java.util.stream.Stream<Path> paths = Files.list(runtimeDirectory)) {
            for (Path path : paths.collect(java.util.stream.Collectors.toList())) {
                String name = path.getFileName().toString();
                boolean managed =
                        name.matches("sandbox-runtime-[0-9]+-[0-9a-f]{16}\\.jar(\\.sha256)?")
                                || name.startsWith("sandbox-runtime-") && name.endsWith(".tmp")
                                || name.startsWith("worker-classpath-") && name.endsWith(".tmp");
                if (managed && !keep.contains(name)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // An old worker can still have the JAR open during a rolling upgrade.
                    }
                }
            }
        }
    }

    private static void grantRuntimeRead(Path path, String offlineSid, String onlineSid)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        List<String> command =
                io.github.sandboxdemo.core.Java8.listOf(
                        java.nio.file.Paths.get(systemRoot, "System32", "icacls.exe").toString(),
                        path.toString(),
                        "/grant",
                        "*" + offlineSid + ":(OI)(CI)(RX)",
                        "*" + onlineSid + ":(OI)(CI)(RX)",
                        "/T",
                        "/Q");
        runAclCommand(command, "grant sandbox accounts read access to " + path);
    }

    private static void removeRuntimeAccess(WindowsSandboxInstallation installation)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        for (Path path :
                io.github.sandboxdemo.core.Java8.listOf(
                        installation.javaHome(),
                        WindowsWorkerRuntime.runtimeDirectory(installation.home()))) {
            if (!Files.exists(path)) {
                continue;
            }
            List<String> command =
                    io.github.sandboxdemo.core.Java8.listOf(
                            java.nio.file.Paths.get(systemRoot, "System32", "icacls.exe")
                                    .toString(),
                            path.toString(),
                            "/remove:g",
                            "*" + installation.offline().sid(),
                            "*" + installation.online().sid(),
                            "/T",
                            "/Q");
            runAclCommand(command, "remove sandbox runtime ACLs from " + path);
        }
    }

    private static void cleanupWorkspaceAccess(WindowsSandboxInstallation installation)
            throws SandboxException, InterruptedException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        WindowsAclLedger ledger = new WindowsAclLedger(installation.home());
        for (WindowsAclLedger.Entry entry : ledger.entries()) {
            if (!Files.exists(entry.path())) {
                continue;
            }
            List<String> command =
                    io.github.sandboxdemo.core.Java8.listOf(
                            java.nio.file.Paths.get(systemRoot, "System32", "icacls.exe")
                                    .toString(),
                            entry.path().toString(),
                            "/remove:g",
                            "*" + entry.sid(),
                            "/Q");
            runAclCommand(command, "remove sandbox workspace ACL from " + entry.path());
        }
        ledger.delete();
    }

    private static void runAclCommand(List<String> command, String operation)
            throws SandboxException, InterruptedException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = io.github.sandboxdemo.core.Java8.readAllBytes(process.getInputStream());
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
}
