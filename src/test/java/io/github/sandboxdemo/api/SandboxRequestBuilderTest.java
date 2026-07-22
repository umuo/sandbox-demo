package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SandboxRequestBuilderTest {

    @Test
    void buildsAnAgentRequestWithoutImplicitShellParsing() {
        Path workspace =
                java.nio.file.Paths.get("target", "sdk-builder-workspace").toAbsolutePath();

        SandboxRequest request =
                SandboxRequest.builder(workspace, "/usr/bin/git")
                        .arguments("status", "--short")
                        .environment("LANG", "C.UTF-8")
                        .standardInputUtf8("request body")
                        .network(NetworkPolicy.DENY)
                        .readPolicy(ReadPolicy.DECLARED_ONLY)
                        .timeout(Duration.ofSeconds(7))
                        .maxOutputBytes(1024)
                        .build();

        assertEquals("/usr/bin/git", request.command().executable());
        assertEquals(
                io.github.sandboxdemo.core.Java8.listOf("status", "--short"),
                request.command().arguments());
        assertEquals("C.UTF-8", request.environment().get("LANG"));
        assertEquals(
                "request body",
                new String(request.standardInput(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(Duration.ofSeconds(7), request.policy().timeout());
    }

    @Test
    void reservedEnvironmentVariablesRemainSandboxControlled() {
        Path workspace =
                java.nio.file.Paths.get("target", "sdk-builder-workspace").toAbsolutePath();
        SandboxRequest.Builder request =
                SandboxRequest.builder(workspace, "/bin/true").environment("HOME", "/tmp/fake");

        assertThrows(IllegalArgumentException.class, request::build);
    }

    @Test
    void standardInputIsBoundedAndDefensivelyCopied() {
        Path workspace =
                java.nio.file.Paths.get("target", "sdk-builder-workspace").toAbsolutePath();
        byte[] input = new byte[] {1, 2, 3};
        SandboxRequest request =
                SandboxRequest.builder(workspace, "/bin/true").standardInput(input).build();

        input[0] = 9;
        byte[] returned = request.standardInput();
        returned[1] = 9;
        assertNotEquals(9, request.standardInput()[0]);
        assertNotEquals(9, request.standardInput()[1]);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SandboxRequest.builder(workspace, "/bin/true")
                                .standardInput(new byte[8 * 1024 * 1024 + 1])
                                .build());
    }
}
