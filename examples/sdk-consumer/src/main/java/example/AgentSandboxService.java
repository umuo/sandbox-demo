package example;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.api.SandboxRuntimeStatus;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Minimal service facade showing how an Agent should consume the SDK. */
public final class AgentSandboxService {

    private final SandboxClient client;

    public AgentSandboxService() {
        this.client = SandboxClient.create();
    }

    public SandboxRuntimeStatus status() {
        return client.status();
    }

    public SandboxResult execute(
            Path workspace,
            CommandSpec command,
            Map<String, String> explicitEnvironment)
            throws SandboxException, InterruptedException {

        ReadPolicy reads =
                client.capabilities().supports(ReadPolicy.DECLARED_ONLY)
                        ? ReadPolicy.DECLARED_ONLY
                        : ReadPolicy.HOST;
        NetworkPolicy network =
                client.capabilities().supports(NetworkPolicy.DENY)
                        ? NetworkPolicy.DENY
                        : NetworkPolicy.ALLOW;
        SandboxRequest request =
                SandboxRequest.builder(workspace, command)
                        .protect(workspace.resolve(".git"))
                        .readPolicy(reads)
                        .network(network)
                        .timeout(Duration.ofMinutes(2))
                        .maxOutputBytes(4 * 1024 * 1024)
                        .environment(explicitEnvironment)
                        .build();
        return client.execute(request);
    }
}
