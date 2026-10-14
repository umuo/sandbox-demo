package example;

import io.github.sandboxdemo.api.CommandSpec;
import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.SandboxCompletion;
import io.github.sandboxdemo.api.SandboxExecution;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxPlatform;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Java 8 消费示例：取消固定 POSIX 工具并检查独立回收状态。
 *
 * <p>仅适用于 Linux/macOS 正常宿主终端；不注册原文回调、不读取用户数据。
 */
public final class SandboxCancellationExample {
    private SandboxCancellationExample() {}

    public static void main(String[] args) throws Exception {
        if (SandboxPlatform.current() == SandboxPlatform.WINDOWS) {
            throw new IllegalStateException("this fixed POSIX example requires Linux or macOS");
        }
        Path workspace = Files.createTempDirectory("sdk-cancellation-example-");
        ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(2), new ThreadPoolExecutor.AbortPolicy());
        SandboxExecution execution = null;
        try {
            SandboxClient client = SandboxClient.builder().cleanupGrace(Duration.ofSeconds(2)).build();
            SandboxRequest request = SandboxRequest.builder(workspace,
                    CommandSpec.of("/bin/sh", "-c", "printf ready > ready; sleep 2"))
                    .network(NetworkPolicy.DENY).timeout(Duration.ofSeconds(5)).build();
            execution = client.start(request, workers);
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(workspace.resolve("ready")) && readyDeadline - System.nanoTime() > 0
                    && !execution.completion().isPresent()) {
                Thread.sleep(10);
            }
            execution.cancel();
            SandboxCompletion completion = execution.await(Duration.ofSeconds(3));
            System.out.println(completion);
            System.out.println(execution.cleanup());
            if (!execution.cleanup().confirmed()) {
                System.out.println("Host reconciliation required; retain tenant execution capacity.");
            }
        } finally {
            if (execution != null) { execution.cancel(); }
            workers.shutdown();
            workers.awaitTermination(3, TimeUnit.SECONDS);
            // The fixed command only writes this marker. The host owns the application workspace.
            Files.deleteIfExists(workspace.resolve("ready"));
            Files.deleteIfExists(workspace);
        }
    }
}
