package io.github.sandboxdemo.platform.windows;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Crypt32Util;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;

/** Versioned, DPAPI-protected metadata for the dedicated Windows accounts. */
final class WindowsSandboxInstallation {

    static final int VERSION = 1;
    static final String DEFAULT_OFFLINE_USER = "AgentSbxOffline";
    static final String DEFAULT_ONLINE_USER = "AgentSbxOnline";
    private static final String FILE_NAME = "windows-sandbox.properties";

    private final Path home;
    private final Path javaHome;
    private final Credential offline;
    private final Credential online;

    WindowsSandboxInstallation(Path home, Path javaHome, Credential offline, Credential online) {
        this.home = home.toAbsolutePath().normalize();
        this.javaHome = javaHome.toAbsolutePath().normalize();
        this.offline = Objects.requireNonNull(offline, "offline");
        this.online = Objects.requireNonNull(online, "online");
    }

    static Path defaultHome() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "AgentSandboxSdk").toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".agent-sandbox-sdk")
                .toAbsolutePath()
                .normalize();
    }

    static WindowsSandboxInstallation load(Path home) throws SandboxException {
        Path file = home.toAbsolutePath().normalize().resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            throw new SandboxBackendUnavailableException(
                    "the production Windows sandbox is not installed; run `setup-windows --home "
                            + home.toAbsolutePath().normalize()
                            + "` from an elevated terminal");
        }
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
        } catch (IOException e) {
            throw new SandboxException("failed to read Windows sandbox installation metadata", e);
        }
        int version;
        try {
            version = Integer.parseInt(required(values, "version"));
        } catch (NumberFormatException e) {
            throw new SandboxException("invalid Windows sandbox installation version", e);
        }
        if (version != VERSION) {
            throw new SandboxBackendUnavailableException(
                    "Windows sandbox installation version "
                            + version
                            + " is incompatible with runtime version "
                            + VERSION);
        }
        return new WindowsSandboxInstallation(
                home,
                Path.of(required(values, "java.home")),
                credential(values, "offline"),
                credential(values, "online"));
    }

    void save() throws SandboxException {
        try {
            Files.createDirectories(home);
            Properties values = new Properties();
            values.setProperty("version", Integer.toString(VERSION));
            values.setProperty("java.home", javaHome.toString());
            putCredential(values, "offline", offline);
            putCredential(values, "online", online);
            Path temporary = Files.createTempFile(home, "windows-sandbox-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "DPAPI-protected Windows sandbox credentials");
            }
            Files.move(
                    temporary,
                    home.resolve(FILE_NAME),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new SandboxException("failed to save Windows sandbox installation metadata", e);
        }
    }

    Path home() {
        return home;
    }

    Path javaHome() {
        return javaHome;
    }

    Credential offline() {
        return offline;
    }

    Credential online() {
        return online;
    }

    void verifyAccounts() throws SandboxException {
        verifyAccount(offline);
        verifyAccount(online);
    }

    private static void verifyAccount(Credential credential) throws SandboxException {
        try {
            String actualSid = Advapi32Util.getAccountByName(null, credential.username()).sidString;
            if (!actualSid.equalsIgnoreCase(credential.sid())) {
                throw new SandboxException(
                        "Windows sandbox account SID changed; rerun uninstall/setup: "
                                + credential.username());
            }
        } catch (SandboxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SandboxException(
                    "Windows sandbox account is missing: " + credential.username(), e);
        }
    }

    private static Credential credential(Properties values, String prefix) throws SandboxException {
        String encrypted = required(values, prefix + ".password.dpapi");
        byte[] plaintext;
        try {
            plaintext = Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encrypted));
        } catch (RuntimeException e) {
            throw new SandboxException(
                    "failed to decrypt "
                            + prefix
                            + " sandbox credentials; setup and run must use the same Windows user",
                    e);
        }
        String password = new String(plaintext, StandardCharsets.UTF_8);
        java.util.Arrays.fill(plaintext, (byte) 0);
        return new Credential(
                required(values, prefix + ".username"),
                required(values, prefix + ".sid"),
                password);
    }

    private static void putCredential(Properties values, String prefix, Credential credential) {
        values.setProperty(prefix + ".username", credential.username());
        values.setProperty(prefix + ".sid", credential.sid());
        byte[] plaintext = credential.password().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = Crypt32Util.cryptProtectData(plaintext);
        java.util.Arrays.fill(plaintext, (byte) 0);
        values.setProperty(
                prefix + ".password.dpapi", Base64.getEncoder().encodeToString(encrypted));
    }

    private static String required(Properties values, String name) throws SandboxException {
        String value = values.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new SandboxException("missing Windows sandbox installation value: " + name);
        }
        return value;
    }

    record Credential(String username, String sid, String password) {

        Credential {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(sid, "sid");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "Credential[username=" + username + ", sid=" + sid + ", password=<redacted>]";
        }
    }
}
