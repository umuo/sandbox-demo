package example;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.time.Duration;

/** Java 8: the host supplies workspace and toolchain paths. */
public final class SandboxStrictEnvironmentExample {
    private SandboxStrictEnvironmentExample() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Supply an existing trusted private parent directory");
        }
        boolean windows = SandboxPlatform.current() == SandboxPlatform.WINDOWS;
        Path tools = windows
                ? Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32")
                : Paths.get("/usr/bin");
        SandboxWorkspace workspace = SandboxWorkspace.create(Paths.get(args[0]));
        try {
            SandboxRequest.Builder builder = workspace.request(windows ? "cmd.exe" : "env")
                    .executionEnvironment(SandboxEnvironment.strict(tools).allowVariable("APP_MODE"))
                    .environment("APP_MODE", "synthetic").allowPathSearch(true)
                    .timeout(Duration.ofSeconds(30));
            if (windows) { builder.arguments("/d", "/s", "/c", "set"); }
            // This fixed trusted program prints its environment and creates no descendants.
            // Do not print output contents in default logs.
            SandboxResult result = SandboxClient.create().execute(builder.build());
            System.out.println(result);
        } finally {
            // For arbitrary or asynchronous tools, wait for execution and inspect independent
            // process/stream cleanup before deleting a workspace still in use.
            System.out.println("Workspace cleanup: " + workspace.cleanup());
        }
    }
}
