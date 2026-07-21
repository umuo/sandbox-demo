package example;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Windows example: project/AppData readable, only src/test/java writable, network allowed. */
public final class WindowsSelectiveWriteExample {

    private final SandboxClient client = SandboxClient.create();

    /**
     * Runs a self-contained demonstration against the supplied project directory (or the current
     * directory when omitted).
     */
    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            throw new IllegalStateException("This example must be run on Windows");
        }
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "usage: WindowsSelectiveWriteExample [project-directory]");
        }

        Path project = (args.length == 0 ? Path.of(".") : Path.of(args[0])).toRealPath();
        String demonstration = demonstrationCommand(project);
        SandboxResult result =
                new WindowsSelectiveWriteExample().execute(project, demonstration);

        System.out.print(result.stdoutUtf8());
        System.err.print(result.stderrUtf8());
        System.out.printf(
                "exitCode=%d, timedOut=%s, duration=%s%n",
                result.exitCode(), result.timedOut(), result.duration());
        if (!result.successful()) {
            throw new IllegalStateException("Windows sandbox demonstration failed");
        }
    }

    public SandboxResult execute(Path projectDirectory, String modelGeneratedPowerShell)
            throws IOException, SandboxException, InterruptedException {

        Path project = projectDirectory.toRealPath();
        Path writableTests = project.resolve("src").resolve("test").resolve("java").toRealPath();
        Path appData = resolveAppData();
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        String powershell =
                Path.of(
                                systemRoot,
                                "System32",
                                "WindowsPowerShell",
                                "v1.0",
                                "powershell.exe")
                        .toString();

        SandboxRequest request =
                SandboxRequest.builder(project, powershell)
                        .arguments(
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-Command",
                                modelGeneratedPowerShell)
                        // The builder normally makes cwd writable. Remove that implicit grant.
                        .readOnlyWorkingDirectory()
                        // The project is already an implicit readable root because it is cwd.
                        .readableRoot(appData)
                        .writableRoot(writableTests)
                        // Native Windows supports broad host reads, not a strict read allowlist.
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.ALLOW)
                        .timeout(Duration.ofMinutes(2))
                        .maxOutputBytes(4 * 1024 * 1024)
                        .build();

        return client.execute(request);
    }

    private static Path resolveAppData() throws IOException {
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile == null || userProfile.isBlank()) {
            throw new IllegalStateException("USERPROFILE is unavailable");
        }
        Path appData = Path.of(userProfile, "AppData");
        if (!Files.isDirectory(appData)) {
            throw new IllegalStateException("AppData directory is unavailable: " + appData);
        }
        return appData.toRealPath();
    }

    private static String demonstrationCommand(Path project) throws IOException {
        Path writable = project.resolve("src/test/java/sandbox-write-allowed.txt");
        Path blocked = project.resolve("sandbox-write-must-be-blocked.txt");
        Path appData = resolveAppData();

        return "$ErrorActionPreference='Stop';"
                + "Set-Content -LiteralPath '"
                + quotePowerShell(writable)
                + "' -Value 'allowed';"
                + "Write-Output 'WRITE src\\test\\java: ALLOWED';"
                + "$writeWasBlocked=$false;try {"
                + "Set-Content -LiteralPath '"
                + quotePowerShell(blocked)
                + "' -Value 'blocked';"
                + "} catch { $writeWasBlocked=$true };"
                + "if (-not $writeWasBlocked) {"
                + "Remove-Item -LiteralPath '"
                + quotePowerShell(blocked)
                + "' -Force;throw 'project root was unexpectedly writable'"
                + "};Write-Output 'WRITE project root: BLOCKED';"
                + "Get-ChildItem -LiteralPath '"
                + quotePowerShell(appData)
                + "' | Select-Object -First 1 | ForEach-Object {"
                + "Write-Output ('READ AppData: ALLOWED (' + $_.Name + ')')"
                + "};"
                + "Write-Output 'NETWORK policy: ALLOW (no SDK network restriction)'";
    }

    private static String quotePowerShell(Path path) {
        return path.toString().replace("'", "''");
    }
}
