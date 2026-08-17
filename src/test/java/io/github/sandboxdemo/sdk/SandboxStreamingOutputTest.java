package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxOutputListener;
import io.github.sandboxdemo.api.SandboxRequest;
import io.github.sandboxdemo.api.SandboxResult;
import io.github.sandboxdemo.core.OutputCallbacks;
import io.github.sandboxdemo.core.ProcessExecutor;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxStreamingOutputTest {

    @Test
    void builderRegistersStdoutAndStderrByteAndTextConsumers() {
        Path workspace = Paths.get("target", "streaming-test-workspace").toAbsolutePath();
        List<byte[]> stdoutByteChunks = new ArrayList<>();
        List<String> stdoutTextChunks = new ArrayList<>();
        List<byte[]> stderrByteChunks = new ArrayList<>();
        List<String> stderrTextChunks = new ArrayList<>();

        SandboxRequest request =
                SandboxRequest.builder(workspace, "/bin/sh")
                        .stdoutConsumer(stdoutByteChunks::add)
                        .stdoutTextConsumer(stdoutTextChunks::add)
                        .stderrConsumer(stderrByteChunks::add)
                        .stderrTextConsumer(stderrTextChunks::add)
                        .build();

        byte[] stdoutSample = "hello stdout".getBytes(StandardCharsets.UTF_8);
        byte[] stderrSample = "hello stderr".getBytes(StandardCharsets.UTF_8);

        request.stdoutConsumer().accept(stdoutSample);
        request.stderrConsumer().accept(stderrSample);
        request.stdoutTextConsumer().accept("hello stdout");
        request.stderrTextConsumer().accept("hello stderr");

        assertEquals(1, stdoutByteChunks.size());
        assertSame(stdoutSample, stdoutByteChunks.get(0));
        assertEquals(1, stdoutTextChunks.size());
        assertEquals("hello stdout", stdoutTextChunks.get(0));

        assertEquals(1, stderrByteChunks.size());
        assertSame(stderrSample, stderrByteChunks.get(0));
        assertEquals(1, stderrTextChunks.size());
        assertEquals("hello stderr", stderrTextChunks.get(0));
    }

    @Test
    void builderRegistersSandboxOutputListener() {
        Path workspace = Paths.get("target", "streaming-test-workspace").toAbsolutePath();
        List<String> events = new ArrayList<>();

        SandboxOutputListener listener =
                new SandboxOutputListener() {
                    @Override
                    public void onStdout(byte[] chunk) {
                        events.add("stdout-bytes:" + new String(chunk, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onStderr(byte[] chunk) {
                        events.add("stderr-bytes:" + new String(chunk, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onStdoutText(String text) {
                        events.add("stdout-text:" + text);
                    }

                    @Override
                    public void onStderrText(String text) {
                        events.add("stderr-text:" + text);
                    }
                };

        SandboxRequest request =
                SandboxRequest.builder(workspace, "/bin/sh").outputListener(listener).build();

        request.stdoutConsumer().accept("line 1".getBytes(StandardCharsets.UTF_8));
        request.stderrConsumer().accept("err 1".getBytes(StandardCharsets.UTF_8));
        request.stdoutTextConsumer().accept("line 1");
        request.stderrTextConsumer().accept("err 1");

        assertTrue(events.contains("stdout-bytes:line 1"));
        assertTrue(events.contains("stdout-text:line 1"));
        assertTrue(events.contains("stderr-bytes:err 1"));
        assertTrue(events.contains("stderr-text:err 1"));
    }

    @Test
    void utf8TextConsumerPreservesCharactersSplitAcrossByteChunks() {
        List<String> chunks = new ArrayList<>();
        byte[] encoded = "你a".getBytes(StandardCharsets.UTF_8);
        OutputCallbacks callbacks = new OutputCallbacks(null, chunks::add);

        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 0, 2));
        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 2, encoded.length));
        callbacks.complete();

        assertEquals("你a", String.join("", chunks));
    }

    @Test
    void resultSupportsExplicitAndAutomaticLegacyCharsetFallback() {
        Charset gbk = Charset.forName("GBK");
        byte[] error = "中文错误".getBytes(gbk);
        SandboxResult result =
                new SandboxResult(1, false, new byte[0], error, false, false, Duration.ZERO);

        assertEquals("中文错误", result.stderrText(gbk));
        assertEquals("中文错误", result.stderrTextAuto(gbk));
    }

    @Test
    void autoDetectedStreamingFallsBackToGbkAcrossChunks() {
        Charset gbk = Charset.forName("GBK");
        byte[] encoded = "prefix 中文错误".getBytes(gbk);
        List<String> chunks = new ArrayList<>();
        OutputCallbacks callbacks = new OutputCallbacks(null, chunks::add, gbk, true);

        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 0, 7));
        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 7, 8));
        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 8, encoded.length));
        callbacks.complete();

        assertEquals("prefix 中文错误", String.join("", chunks));
    }

    @Test
    void autoDetectedStreamingKeepsValidUtf8AcrossChunks() {
        byte[] encoded = "prefix 中文错误".getBytes(StandardCharsets.UTF_8);
        List<String> chunks = new ArrayList<>();
        OutputCallbacks callbacks =
                new OutputCallbacks(null, chunks::add, Charset.forName("GBK"), true);

        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 0, 8));
        callbacks.accept(java.util.Arrays.copyOfRange(encoded, 8, encoded.length));
        callbacks.complete();

        assertEquals("prefix 中文错误", String.join("", chunks));
    }

    @Test
    void autoDetectionHonorsUtf16Bom() {
        byte[] text = "中文错误".getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[text.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(text, 0, withBom, 2, text.length);
        SandboxResult result =
                new SandboxResult(1, false, new byte[0], withBom, false, false, Duration.ZERO);

        assertEquals("中文错误", result.stderrTextAuto(Charset.forName("GBK")));
    }

    @Test
    void requestConfiguresAutomaticStreamingFallback() {
        Charset gbk = Charset.forName("GBK");
        SandboxRequest request =
                SandboxRequest.builder(Paths.get("target").toAbsolutePath(), "/bin/sh")
                        .outputCharsetAuto(gbk)
                        .build();

        assertTrue(request.stdoutCharsetAuto());
        assertTrue(request.stderrCharsetAuto());
        assertEquals(gbk, request.stdoutCharset());
        assertEquals(gbk, request.stderrCharset());
    }

    @Test
    void throwingConsumerDoesNotSuppressLaterConsumers() {
        List<String> received = new ArrayList<>();
        SandboxRequest request =
                SandboxRequest.builder(Paths.get("target").toAbsolutePath(), "/bin/sh")
                        .stdoutConsumer(
                                chunk -> {
                                    throw new RuntimeException("intentional callback failure");
                                })
                        .stdoutConsumer(
                                chunk -> received.add(new String(chunk, StandardCharsets.UTF_8)))
                        .build();

        request.stdoutConsumer().accept("still delivered".getBytes(StandardCharsets.UTF_8));

        assertEquals(Collections.singletonList("still delivered"), received);
    }

    @Test
    void consumerExceptionsDoNotDisruptProcessExecutorOrResult() throws Exception {
        Path workspace = Paths.get("target").toAbsolutePath();
        ValidatedPolicy policy =
                new ValidatedPolicy(
                        workspace,
                        Collections.emptyList(),
                        Collections.singletonList(workspace),
                        Collections.emptyList(),
                        workspace.resolve("temp"),
                        NetworkPolicy.ALLOW,
                        ReadPolicy.HOST,
                        io.github.sandboxdemo.api.DeletionPolicy.ALLOW,
                        Duration.ofSeconds(5),
                        1024 * 1024,
                        true);

        StringBuilder output = new StringBuilder();
        // A consumer that throws runtime exception
        java.util.function.Consumer<byte[]> throwingConsumer =
                chunk -> {
                    output.append(new String(chunk, StandardCharsets.UTF_8));
                    throw new RuntimeException("intentional callback failure");
                };

        List<String> command;
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            command =
                    io.github.sandboxdemo.core.Java8.listOf(
                            Paths.get(systemRoot, "System32", "cmd.exe").toString(),
                            "/c",
                            "echo streaming-test");
        } else {
            command =
                    io.github.sandboxdemo.core.Java8.listOf("/bin/sh", "-c", "echo streaming-test");
        }

        SandboxResult result =
                ProcessExecutor.execute(
                        command,
                        policy,
                        Collections.emptyMap(),
                        new byte[0],
                        throwingConsumer,
                        null);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdoutUtf8().contains("streaming-test"));
        assertTrue(output.toString().contains("streaming-test"));
    }
}
