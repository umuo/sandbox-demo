package example;

import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** macOS demo: host-wide reads and writable roots that can change between executions. */
public final class MacOsDynamicPolicyExample {

    private final SandboxClient client = SandboxClient.create();

    public static void main(String[] args) throws Exception {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("mac") && !osName.contains("darwin")) {
            throw new IllegalStateException("This example must be run on macOS");
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("usage: MacOsDynamicPolicyExample");
        }

        Path workspace =
                Files.createTempDirectory("sandbox-macos-dynamic-policy-demo-").toRealPath();
        try {
            Path outputA = Files.createDirectory(workspace.resolve("output-a")).toRealPath();
            Path outputB = Files.createDirectory(workspace.resolve("output-b")).toRealPath();
            Files.write(
                    workspace.resolve("readable.txt"),
                    "host-readable".getBytes(StandardCharsets.UTF_8));
            Files.write(
                    outputA.resolve("existing.txt"), "before".getBytes(StandardCharsets.UTF_8));

            MacOsDynamicPolicyExample example = new MacOsDynamicPolicyExample();
            System.out.println("--- request 1: output-a is writable ---");
            printAndRequireSuccess(
                    example.execute(
                            workspace,
                            java.util.Collections.singletonList(outputA),
                            firstDemonstration(workspace, outputA, outputB)));

            // A policy is immutable for one execution. Build the next request from the latest
            // application rules; no SandboxClient restart or macOS setup is required.
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
            Path workingDirectory, List<Path> currentWritableDirectories, String shellScript)
            throws IOException, SandboxException, InterruptedException {
        Path workspace = workingDirectory.toRealPath();

        SandboxRequest.Builder request =
                SandboxRequest.builder(workspace, "/bin/sh")
                        .arguments("-c", shellScript)
                        .readOnlyWorkingDirectory()
                        .readPolicy(ReadPolicy.HOST)
                        .network(NetworkPolicy.ALLOW)
                        // The macOS Seatbelt backend supports ALLOW, but not DENY, for deletion.
                        .deletion(DeletionPolicy.ALLOW)
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
        return "set -eu;"
                + "value=$(/bin/cat "
                + shellQuote(readable)
                + ");printf '%s\n' \"READ host path: ALLOWED ($value)\";"
                + "printf '%s' 'after' > "
                + shellQuote(existing)
                + ";printf '%s\n' 'MODIFY output-a: ALLOWED';"
                + "printf '%s' 'created' > "
                + shellQuote(created)
                + ";printf '%s\n' 'CREATE output-a: ALLOWED';"
                + requireBlocked(
                        "printf '%s' 'blocked' > " + shellQuote(blocked),
                        "CREATE output-b: BLOCKED");
    }

    private static String secondDemonstration(Path outputA, Path outputB) {
        Path newlyAllowed = outputB.resolve("now-allowed.txt");
        Path noLongerAllowed = outputA.resolve("must-not-be-created.txt");
        return "set -eu;"
                + "printf '%s' 'allowed' > "
                + shellQuote(newlyAllowed)
                + ";printf '%s\n' 'CREATE output-b: ALLOWED';"
                + requireBlocked(
                        "printf '%s' 'blocked' > " + shellQuote(noLongerAllowed),
                        "CREATE output-a after policy change: BLOCKED");
    }

    private static String requireBlocked(String operation, String message) {
        return "if { "
                + operation
                + "; } 2>/dev/null; then "
                + "printf '%s\n' 'operation unexpectedly allowed' >&2;exit 1;"
                + "else printf '%s\n' '"
                + message
                + "';fi;";
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
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
