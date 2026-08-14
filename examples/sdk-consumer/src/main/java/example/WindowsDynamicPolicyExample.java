package example;

import io.github.sandboxdemo.api.DeletionPolicy;
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
import java.util.Comparator;
import java.util.List;

/** Windows demo: host-wide reads, selected write roots, and no delete or rename operations. */
public final class WindowsDynamicPolicyExample {

    private final SandboxClient client = SandboxClient.create();

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            throw new IllegalStateException("This example must be run on Windows");
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("usage: WindowsDynamicPolicyExample");
        }

        Path workspace = Files.createTempDirectory("sandbox-dynamic-policy-demo-").toRealPath();
        try {
            Path outputA = Files.createDirectory(workspace.resolve("output-a")).toRealPath();
            Path outputB = Files.createDirectory(workspace.resolve("output-b")).toRealPath();
            Files.write(
                    workspace.resolve("readable.txt"),
                    "host-readable".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(
                    outputA.resolve("existing.txt"),
                    "before".getBytes(java.nio.charset.StandardCharsets.UTF_8));

            WindowsDynamicPolicyExample example = new WindowsDynamicPolicyExample();
            System.out.println("--- request 1: output-a is writable ---");
            printAndRequireSuccess(
                    example.execute(
                            workspace,
                            java.util.Collections.singletonList(outputA),
                            firstDemonstration(workspace, outputA, outputB)));

            // A policy is immutable for one execution. Build the next request from the latest
            // application rules; no SandboxClient restart or Windows setup is required.
            System.out.println("--- request 2: permission changed to output-b ---");
            printAndRequireSuccess(
                    example.execute(
                            workspace,
                            java.util.Collections.singletonList(outputB),
                            secondDemonstration(outputA, outputB)));
        } finally {
            deleteTree(workspace);
        }
    }

    /** Builds a fresh immutable policy snapshot for every command execution. */
    public SandboxResult execute(
            Path workingDirectory, List<Path> currentWritableDirectories, String powershellScript)
            throws IOException, SandboxException, InterruptedException {
        Path workspace = workingDirectory.toRealPath();
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        String powershell =
                java.nio.file.Paths.get(
                                systemRoot,
                                "System32",
                                "WindowsPowerShell",
                                "v1.0",
                                "powershell.exe")
                        .toString();

        SandboxRequest.Builder request =
                SandboxRequest.builder(workspace, powershell)
                        .arguments(
                                "-NoLogo",
                                "-NoProfile",
                                "-NonInteractive",
                                "-Command",
                                powershellScript)
                        .readOnlyWorkingDirectory()
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.ALLOW)
                        .deletion(DeletionPolicy.DENY)
                        .timeout(Duration.ofMinutes(2));
        for (Path writableDirectory : currentWritableDirectories) {
            request.writableRoot(writableDirectory.toRealPath());
        }
        return client.execute(request.build());
    }

    private static String firstDemonstration(Path workspace, Path outputA, Path outputB) {
        Path readable = workspace.resolve("readable.txt");
        Path existing = outputA.resolve("existing.txt");
        Path created = outputA.resolve("created.txt");
        Path blocked = outputB.resolve("must-not-be-created.txt");
        return "$ErrorActionPreference='Stop';"
                + "$value=Get-Content -LiteralPath '"
                + quote(readable)
                + "';Write-Output ('READ host path: ALLOWED ('+$value+')');"
                + "Set-Content -LiteralPath '"
                + quote(existing)
                + "' -Value 'after';Write-Output 'MODIFY output-a: ALLOWED';"
                + "Set-Content -LiteralPath '"
                + quote(created)
                + "' -Value 'created';Write-Output 'CREATE output-a: ALLOWED';"
                + requireBlocked(
                        "Remove-Item -LiteralPath '" + quote(existing) + "' -Force",
                        "DELETE existing file: BLOCKED")
                + requireBlocked(
                        "Set-Content -LiteralPath '" + quote(blocked) + "' -Value 'blocked'",
                        "CREATE output-b: BLOCKED");
    }

    private static String secondDemonstration(Path outputA, Path outputB) {
        Path newlyAllowed = outputB.resolve("now-allowed.txt");
        Path noLongerAllowed = outputA.resolve("must-not-be-modified.txt");
        return "$ErrorActionPreference='Stop';"
                + "Set-Content -LiteralPath '"
                + quote(newlyAllowed)
                + "' -Value 'allowed';Write-Output 'CREATE output-b: ALLOWED';"
                + requireBlocked(
                        "Set-Content -LiteralPath '"
                                + quote(noLongerAllowed)
                                + "' -Value 'blocked'",
                        "CREATE output-a after policy change: BLOCKED")
                + requireBlocked(
                        "Remove-Item -LiteralPath '" + quote(newlyAllowed) + "' -Force",
                        "DELETE newly created file: BLOCKED");
    }

    private static String requireBlocked(String operation, String message) {
        return "$blocked=$false;try {"
                + operation
                + "} catch {$blocked=$true};if(-not $blocked){throw 'operation unexpectedly allowed'};"
                + "Write-Output '"
                + message
                + "';";
    }

    private static String quote(Path path) {
        return path.toString().replace("'", "''");
    }

    private static void printAndRequireSuccess(SandboxResult result) {
        System.out.print(result.stdoutUtf8());
        System.err.print(result.stderrUtf8());
        if (!result.successful()) {
            throw new IllegalStateException("sandbox demonstration failed: " + result.exitCode());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            Path[] descending = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path path : descending) {
                Files.deleteIfExists(path);
            }
        }
    }
}
