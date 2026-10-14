package example;

import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.sdk.SandboxClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * <h2>  </h2>
 *
 * @description:
 * @menu
 * @author: gitsilence
 * @description:
 * @date: 2026/8/24 14:45
 **/
public class SandboxQuickExample {

    private static final SandboxClient CLIENT = SandboxClient.create();

    public static void main(String[] args) throws IOException, SandboxException, InterruptedException {
        testDeleteFile();
    }

    private static void testDeleteFile() throws IOException, SandboxException, InterruptedException {
        Path workspace = Files.createTempDirectory("sandbox-test-demo-").toRealPath();
        Path aaa = workspace.resolve("aaa");
        Files.createDirectories(aaa);
        Path filePath = aaa.resolve("readable.txt");
        Files.write(filePath, "host-readable".getBytes(StandardCharsets.UTF_8));

        SandboxRequest request = SandboxRequest.builder(workspace, "bash")
                .allowPathSearch(true)
                .readOnlyWorkingDirectory()
                .readPolicy(ReadPolicy.HOST)
                // .writableRoot(aaa)
                .arguments("-c", "rm -f " + filePath)
                .build();

        System.out.println(filePath.toAbsolutePath());
        System.out.println(filePath.toFile().exists());
        SandboxResult result = CLIENT.execute(request);
        System.out.println(result);
        System.out.println(result.stderrTextAuto());
        System.out.println("----------------------------");
        System.out.println(result.stderrTextAuto());
        System.out.println(filePath.toFile().exists());
    }
}
