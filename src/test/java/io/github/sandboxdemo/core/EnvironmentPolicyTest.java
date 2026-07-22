package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentPolicyTest {

    @TempDir Path workspace;

    @Test
    void excludesHostSecretsButIncludesExplicitValues() throws Exception {
        SandboxPolicy policy = SandboxPolicy.builder(workspace).build();
        SandboxRequest request =
                new SandboxRequest(
                        policy,
                        CommandSpec.of("tool"),
                        io.github.sandboxdemo.core.Java8.mapOf("EXPLICIT_VALUE", "ok"));

        ValidatedPolicy validated = PathPolicyValidator.validate(policy);
        try {
            Map<String, String> environment = EnvironmentPolicy.build(request, validated);

            assertFalse(environment.containsKey("OPENAI_API_KEY"));
            assertFalse(environment.containsKey("AWS_SECRET_ACCESS_KEY"));
            assertEquals("ok", environment.get("EXPLICIT_VALUE"));
            assertEquals(validated.privateTempDirectory().toString(), environment.get("TEMP"));
        } finally {
            PathPolicyValidator.cleanup(validated);
        }
    }

    @Test
    void callerCannotOverrideSandboxControlledDirectories() {
        SandboxPolicy policy = SandboxPolicy.builder(workspace).build();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SandboxRequest(
                                policy,
                                CommandSpec.of("tool"),
                                io.github.sandboxdemo.core.Java8.mapOf(
                                        "TEMP", workspace.resolve("escape").toString())));
    }
}
