package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxCapabilities;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRunner;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SandboxClientTest {

    @Test
    void facadeDelegatesTheImmutableRequestToTheSelectedStrategy() throws Exception {
        AtomicReference<SandboxRequest> observed = new AtomicReference<>();
        SandboxResult expected =
                new SandboxResult(
                        0,
                        false,
                        "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        new byte[0],
                        false,
                        false,
                        Duration.ofMillis(2));
        SandboxRunner fake =
                new SandboxRunner() {
                    @Override
                    public String backendName() {
                        return "test-strategy";
                    }

                    @Override
                    public SandboxResult execute(SandboxRequest request) {
                        observed.set(request);
                        return expected;
                    }
                };
        SandboxCapabilities capabilities =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        fake.backendName(),
                        java.util.Set.of(ReadPolicy.DECLARED_ONLY),
                        java.util.Set.of(NetworkPolicy.DENY),
                        false);
        SandboxClient client =
                SandboxClient.builder()
                        .backend(
                                fake,
                                capabilities,
                                () -> new SandboxRuntimeStatus(capabilities, true, "test ready"))
                        .build();
        Path workspace = Path.of("target", "sdk-client-workspace").toAbsolutePath();
        SandboxPolicy policy = SandboxPolicy.builder(workspace).build();
        SandboxRequest request = SandboxRequest.of(policy, CommandSpec.of("/bin/true"));

        SandboxResult actual = client.execute(request);

        assertSame(request, observed.get());
        assertSame(expected, actual);
        assertEquals("test-strategy", client.backendName());
        assertEquals("test-strategy", client.capabilities().backendName());
        assertTrue(client.capabilities().supports(policy.networkPolicy()));
    }
}
