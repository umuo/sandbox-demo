package io.github.sandboxdemo.demo;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.DeletionPolicy;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.core.OperatingSystem;
import io.github.sandboxdemo.sdk.SandboxClient;
import io.github.sandboxdemo.sdk.SandboxRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runnable demo and small CLI adapter suitable for Java ProcessBuilder callers. */
public final class SandboxCli {

    private SandboxCli() {}

    public static void main(String[] args) {
        try {
            int exitCode;
            if (args.length == 0 || "demo".equals(args[0])) {
                exitCode =
                        runDemo(
                                args.length > 1
                                        ? java.nio.file.Paths.get(args[1])
                                        : java.nio.file.Paths.get(".sandbox-demo"));
            } else if ("status".equals(args[0])) {
                SandboxClient.Builder client = SandboxClient.builder();
                Path productionHome = parseOptionalWindowsHome(args);
                if (productionHome != null) {
                    client.windowsProductionHome(productionHome);
                }
                SandboxRuntimeStatus status = client.build().status();
                System.out.println("backend    : " + status.capabilities().backendName());
                System.out.println("platform   : " + status.capabilities().platform());
                System.out.println("enforcement: " + status.capabilities().enforcement());
                System.out.println("ready      : " + status.ready());
                System.out.println("diagnostic : " + status.diagnostic());
                exitCode = status.ready() ? 0 : 3;
            } else if ("setup-windows".equals(args[0])) {
                SandboxRuntime.installWindows(parseWindowsHome(args));
                System.out.println("Windows production sandbox installed.");
                exitCode = 0;
            } else if ("uninstall-windows".equals(args[0])) {
                SandboxRuntime.uninstallWindows(parseWindowsHome(args));
                System.out.println("Windows production sandbox uninstalled.");
                exitCode = 0;
            } else {
                exitCode = runCommand(args);
            }
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (Exception e) {
            System.err.println("sandbox error: " + e.getMessage());
            if (Boolean.parseBoolean(System.getenv("SANDBOX_DEBUG"))) {
                e.printStackTrace(System.err);
            }
            System.exit(125);
        }
    }

    private static int runCommand(String[] args) throws Exception {
        if (!"run".equals(args[0])) {
            printUsage();
            return 2;
        }

        Path cwd = null;
        List<Path> readable = new ArrayList<>();
        List<Path> writable = new ArrayList<>();
        List<Path> protectedPaths = new ArrayList<>();
        Map<String, String> environment = new LinkedHashMap<>();
        NetworkPolicy network = null;
        ReadPolicy readPolicy = null;
        DeletionPolicy deletionPolicy = null;
        Duration timeout = Duration.ofSeconds(30);
        int maxOutputBytes = 4 * 1024 * 1024;
        boolean allowPathSearch = false;
        boolean readOnlyWorkingDirectory = false;
        int separator = -1;

        for (int i = 1; i < args.length; i++) {
            if ("--".equals(args[i])) {
                separator = i;
                break;
            }
            switch (args[i]) {
                case "--cwd":
                    cwd = java.nio.file.Paths.get(requireValue(args, ++i, "--cwd"));
                    break;
                case "--readable":
                    readable.add(java.nio.file.Paths.get(requireValue(args, ++i, "--readable")));
                    break;
                case "--writable":
                    writable.add(java.nio.file.Paths.get(requireValue(args, ++i, "--writable")));
                    break;
                case "--read-only-cwd":
                    readOnlyWorkingDirectory = true;
                    break;
                case "--protect":
                    protectedPaths.add(
                            java.nio.file.Paths.get(requireValue(args, ++i, "--protect")));
                    break;
                case "--network":
                    network = parseNetwork(requireValue(args, ++i, "--network"));
                    break;
                case "--read-policy":
                    readPolicy = parseReadPolicy(requireValue(args, ++i, "--read-policy"));
                    break;
                case "--deletion":
                    deletionPolicy = parseDeletion(requireValue(args, ++i, "--deletion"));
                    break;
                case "--timeout":
                    timeout =
                            Duration.ofMillis(Long.parseLong(requireValue(args, ++i, "--timeout")));
                    break;
                case "--max-output-bytes":
                    maxOutputBytes =
                            Integer.parseInt(requireValue(args, ++i, "--max-output-bytes"));
                    break;
                case "--allow-path-search":
                    allowPathSearch = true;
                    break;
                case "--env":
                    addEnvironment(environment, requireValue(args, ++i, "--env"));
                    break;
                default:
                    throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        if (cwd == null || separator < 0 || separator + 1 >= args.length) {
            throw new IllegalArgumentException("--cwd and a command after -- are required");
        }

        SandboxPolicy.Builder policy =
                SandboxPolicy.builder(cwd)
                        .timeout(timeout)
                        .maxOutputBytes(maxOutputBytes)
                        .allowPathSearch(allowPathSearch);
        if (network != null) {
            policy.network(network);
        }
        if (readPolicy != null) {
            policy.readPolicy(readPolicy);
        }
        if (deletionPolicy != null) {
            policy.deletion(deletionPolicy);
        }
        if (readOnlyWorkingDirectory) {
            policy.readOnlyWorkingDirectory();
        }
        readable.forEach(policy::readableRoot);
        writable.forEach(policy::writableRoot);
        protectedPaths.forEach(policy::protect);

        CommandSpec command =
                new CommandSpec(
                        args[separator + 1],
                        io.github.sandboxdemo.core.Java8.listOf(args)
                                .subList(separator + 2, args.length));
        SandboxClient runner = SandboxClient.create();
        SandboxResult result =
                runner.execute(new SandboxRequest(policy.build(), command, environment));
        System.out.write(result.stdout());
        System.err.write(result.stderr());
        return result.timedOut() ? 124 : result.exitCode();
    }

    private static int runDemo(Path demoRoot) throws Exception {
        Path root = demoRoot.toAbsolutePath().normalize();
        Path workspace = root.resolve("workspace");
        Path outside = root.resolve("outside");
        Path protectedDirectory = workspace.resolve("protected");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createDirectories(protectedDirectory);

        Path allowedFile = workspace.resolve("allowed.txt");
        Path blockedFile = outside.resolve("blocked.txt");
        Path protectedFile = protectedDirectory.resolve("blocked.txt");
        Path outsideSecret = outside.resolve("secret.txt");
        Files.deleteIfExists(allowedFile);
        Files.deleteIfExists(blockedFile);
        Files.deleteIfExists(protectedFile);
        io.github.sandboxdemo.core.Java8.writeString(
                outsideSecret, "host-secret-must-not-be-readable");

        ReadPolicy readPolicy =
                OperatingSystem.current() == OperatingSystem.WINDOWS
                        ? ReadPolicy.HOST
                        : ReadPolicy.DECLARED_ONLY;
        SandboxClient runner = SandboxClient.create();
        NetworkPolicy network =
                runner.capabilities().supports(NetworkPolicy.DENY)
                        ? NetworkPolicy.DENY
                        : NetworkPolicy.ALLOW;
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .protect(protectedDirectory)
                        .network(network)
                        .readPolicy(readPolicy)
                        .timeout(Duration.ofSeconds(10))
                        .build();
        System.out.println("backend   : " + runner.backendName());
        System.out.println("workspace : " + workspace);
        System.out.println("outside   : " + outside);

        SandboxResult allowed =
                runner.execute(
                        SandboxRequest.of(policy, writeCommand(allowedFile, "sandbox-write-ok")));
        boolean allowedOk = allowed.successful() && Files.isRegularFile(allowedFile);
        printCheck("workspace write", allowedOk, allowed);

        SandboxResult blocked =
                runner.execute(
                        SandboxRequest.of(policy, writeCommand(blockedFile, "must-not-exist")));
        boolean blockedOk = !Files.exists(blockedFile);
        printCheck("outside write blocked", blockedOk, blocked);

        SandboxResult protectedResult =
                runner.execute(
                        SandboxRequest.of(policy, writeCommand(protectedFile, "must-not-exist")));
        boolean protectedOk = !Files.exists(protectedFile);
        printCheck("protected path blocked", protectedOk, protectedResult);

        boolean readBlocked = true;
        if (readPolicy == ReadPolicy.DECLARED_ONLY) {
            SandboxResult readResult =
                    runner.execute(SandboxRequest.of(policy, readCommand(outsideSecret)));
            readBlocked =
                    !readResult.successful()
                            && !readResult
                                    .stdoutUtf8()
                                    .contains("host-secret-must-not-be-readable");
            printCheck("outside read blocked", readBlocked, readResult);
        } else {
            System.out.println("outside read blocked      : SKIP (Windows workspace-write mode)");
        }

        boolean success = allowedOk && blockedOk && protectedOk && readBlocked;
        System.out.println(success ? "DEMO PASSED" : "DEMO FAILED");
        return success ? 0 : 1;
    }

    private static CommandSpec readCommand(Path target) {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            return CommandSpec.of(
                    java.nio.file.Paths.get(systemRoot, "System32", "cmd.exe").toString(),
                    "/d",
                    "/s",
                    "/c",
                    "type \"" + target + "\"");
        }
        return CommandSpec.of("/bin/sh", "-c", "cat -- \"$1\"", "sandbox-demo", target.toString());
    }

    private static CommandSpec writeCommand(Path target, String content) {
        if (OperatingSystem.current() == OperatingSystem.WINDOWS) {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            String command = "echo " + content + ">\"" + target + "\"";
            return CommandSpec.of(
                    java.nio.file.Paths.get(systemRoot, "System32", "cmd.exe").toString(),
                    "/d",
                    "/s",
                    "/c",
                    command);
        }
        return CommandSpec.of(
                "/bin/sh",
                "-c",
                "printf '%s\\n' \"$1\" > \"$2\"",
                "sandbox-demo",
                content,
                target.toString());
    }

    private static void printCheck(String name, boolean success, SandboxResult result) {
        System.out.printf(
                "%-25s : %s (exit=%d)%n", name, success ? "PASS" : "FAIL", result.exitCode());
        if (!io.github.sandboxdemo.core.Java8.isBlank(result.stderrUtf8())) {
            System.out.println("  stderr: " + result.stderrUtf8().trim());
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args[index];
    }

    private static NetworkPolicy parseNetwork(String value) {
        String normalized = value.toLowerCase();
        if ("allow".equals(normalized)) {
            return NetworkPolicy.ALLOW;
        }
        if ("deny".equals(normalized)) {
            return NetworkPolicy.DENY;
        }
        throw new IllegalArgumentException("network must be allow or deny");
    }

    private static ReadPolicy parseReadPolicy(String value) {
        String normalized = value.toLowerCase();
        if ("declared".equals(normalized) || "declared-only".equals(normalized)) {
            return ReadPolicy.DECLARED_ONLY;
        }
        if ("host".equals(normalized)) {
            return ReadPolicy.HOST;
        }
        throw new IllegalArgumentException("read-policy must be declared-only or host");
    }

    private static DeletionPolicy parseDeletion(String value) {
        String normalized = value.toLowerCase();
        if ("allow".equals(normalized)) {
            return DeletionPolicy.ALLOW;
        }
        if ("deny".equals(normalized)) {
            return DeletionPolicy.DENY;
        }
        throw new IllegalArgumentException("deletion must be allow or deny");
    }

    private static void addEnvironment(Map<String, String> environment, String assignment) {
        int equals = assignment.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException("--env must use KEY=VALUE");
        }
        environment.put(assignment.substring(0, equals), assignment.substring(equals + 1));
    }

    private static Path parseWindowsHome(String[] args) {
        if (args.length == 1) {
            return SandboxRuntime.defaultWindowsHome();
        }
        if (args.length == 3 && "--home".equals(args[1])) {
            return java.nio.file.Paths.get(args[2]);
        }
        throw new IllegalArgumentException(args[0] + " accepts only optional --home PATH");
    }

    private static Path parseOptionalWindowsHome(String[] args) {
        if (args.length == 1) {
            return null;
        }
        if (args.length == 3 && "--home".equals(args[1])) {
            return java.nio.file.Paths.get(args[2]);
        }
        throw new IllegalArgumentException("status accepts only optional --home PATH");
    }

    private static void printUsage() {
        System.err.println(
                io.github.sandboxdemo.core.Java8.lines(
                        "Usage:",
                        "  java -jar sandbox.jar demo [demo-directory]",
                        "  java -jar sandbox.jar status [--home PATH]",
                        "  java -jar sandbox.jar setup-windows [--home PATH]",
                        "  java -jar sandbox.jar uninstall-windows [--home PATH]",
                        "  java -jar sandbox.jar run --cwd PATH [options] -- EXECUTABLE [ARG...]",
                        "",
                        "Options:",
                        "  --read-only-cwd    remove the implicit write grant for --cwd",
                        "  --writable PATH     add a writable root (repeatable)",
                        "  --readable PATH     add a read-only root (repeatable)",
                        "  --protect PATH      make a nested path read-only (repeatable)",
                        "  --read-policy declared-only|host",
                        "  --network allow|deny",
                        "  --deletion allow|deny",
                        "  --timeout MILLIS",
                        "  --max-output-bytes BYTES",
                        "  --allow-path-search explicitly permit resolving outer argv[0] through PATH",
                        "  --env KEY=VALUE     explicitly pass an environment variable"));
    }
}
