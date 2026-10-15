package example;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/** Java 8: inspect an actual policy without executing the requested command. */
public final class SandboxReadinessExample {
    private SandboxReadinessExample() {}

    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("sdk-readiness-example-");
        boolean windows = SandboxPlatform.current() == SandboxPlatform.WINDOWS;
        try {
            SandboxRequest.Builder builder = SandboxRequest.builder(workspace, windows
                    ? Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe").toString()
                    : "/bin/sh");
            if (windows) {
                builder.arguments("/d", "/s", "/c", "echo synthetic>target-marker")
                        .readPolicy(ReadPolicy.HOST).network(NetworkPolicy.ALLOW);
            } else {
                builder.arguments("-c", "printf synthetic > target-marker")
                        .readPolicy(ReadPolicy.DECLARED_ONLY).network(NetworkPolicy.DENY);
            }
            SandboxPolicyReadiness check = SandboxClient.create().readiness(
                    builder.timeout(Duration.ofSeconds(30)).build());
            System.out.println(check);
            if (Files.exists(workspace.resolve("target-marker"))) {
                throw new IllegalStateException("Readiness executed the target command");
            }
            if (!check.ready()) {
                throw check.error().get();
            }
            System.out.println("Trusted probe passed; inspect cleanup separately before releasing capacity.");
        } finally {
            Files.deleteIfExists(workspace);
        }
    }
}
