package example;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.time.Duration;

/** Java 8: require hard limits, and reject hosts that cannot enforce them. */
public final class SandboxResourceAdmissionExample {
    private SandboxResourceAdmissionExample() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Supply a trusted private parent directory"); }
        boolean windows = SandboxPlatform.current() == SandboxPlatform.WINDOWS;
        SandboxWorkspace workspace = SandboxWorkspace.create(Paths.get(args[0]));
        try {
            String executable = windows
                    ? Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe").toString()
                    : "/bin/sh";
            SandboxRequest.Builder builder = workspace.request(executable)
                    .resourceLimits(SandboxResourceLimits.builder().memoryBytes(96L*1024*1024).processes(1).build())
                    .requiredIsolation(SandboxIsolationLevel.OS_PROCESS).timeout(Duration.ofSeconds(30));
            if (windows) { builder.arguments("/d", "/s", "/c", "echo synthetic"); }
            else { builder.arguments("-c", "printf synthetic"); }
            SandboxPolicyReadiness check = SandboxClient.create().readiness(builder.build());
            System.out.println(check);
            if (!check.ready()) {
                if (check.error().get().code() == SandboxErrorCode.POLICY_UNSUPPORTED) {
                    System.out.println("Admission rejected: supply a host backend that enforces these limits.");
                } else { throw check.error().get(); }
            }
            // Example only checks admission. The host must bind the same request and inspect
            // independent cleanup evidence before managing arbitrary tool execution.
        } finally { System.out.println("Workspace cleanup: " + workspace.cleanup()); }
    }
}
