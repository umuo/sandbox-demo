package example;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

/** Java 8 example: reject incomplete synthetic output and inspect cleanup independently. */
public final class SandboxOutputLimitExample {
    private SandboxOutputLimitExample() {}

    public static void main(String[] args) throws Exception {
        boolean windows = SandboxPlatform.current() == SandboxPlatform.WINDOWS;
        Path workspace = Files.createTempDirectory("sdk-output-example-");
        ThreadPoolExecutor workers = new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1),new ThreadPoolExecutor.AbortPolicy());
        SandboxExecution execution = null;
        try {
            SandboxRequest.Builder request = SandboxRequest.builder(workspace, windows
                    ? java.nio.file.Paths.get(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","cmd.exe").toString()
                    : "/bin/sh");
            if (windows) {
                request.arguments("/d","/s","/c","for /l %i in (1,1,1000) do @echo synthetic-output")
                        .readPolicy(ReadPolicy.HOST).network(NetworkPolicy.ALLOW);
            } else {
                request.arguments("-c","i=0; while [ $i -lt 1000 ]; do printf synthetic-output; i=$((i+1)); done")
                        .readPolicy(ReadPolicy.DECLARED_ONLY).network(NetworkPolicy.DENY);
            }
            execution = SandboxClient.create().start(request.maxOutputBytes(1024).maxCombinedOutputBytes(64)
                    .onOutputLimit(OutputLimitAction.TERMINATE).outputCallbackMode(OutputCallbackMode.RETAINED)
                    .timeout(Duration.ofSeconds(10)).build(), workers);
            SandboxCompletion completion = execution.await(Duration.ofSeconds(13));
            System.out.println(completion); // Metadata only; no stdout/stderr contents.
            if (!completion.completeSuccessful()) {
                System.out.println("Output delivery rejected. Consult independent cleanup before releasing capacity.");
            }
        } finally {
            if (execution != null) { execution.cancel(); }
            workers.shutdown(); workers.awaitTermination(3,TimeUnit.SECONDS);
            // The fixed synthetic tool writes no application files; the host owns this workspace.
            Files.deleteIfExists(workspace);
        }
    }
}
