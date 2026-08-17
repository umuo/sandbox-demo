package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.sandboxdemo.core.OutputCallbacks;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class WindowsWorkerStreamingTest {

    @TempDir Path root;

    @Test
    @Timeout(5)
    void transfersBoundedChunksAndPreservesSplitUtf8() throws Exception {
        Path chunkFile = root.resolve("stdout.stream");
        AtomicBoolean complete = new AtomicBoolean();
        List<String> textChunks = new ArrayList<>();
        ExecutorService readers = Executors.newSingleThreadExecutor();
        Future<?> reader =
                readers.submit(
                        () -> {
                            WindowsWorkerLauncher.streamFile(
                                    chunkFile,
                                    new OutputCallbacks(null, textChunks::add),
                                    complete);
                            return null;
                        });
        try {
            WindowsSandboxWorker.StreamFileConsumer writer =
                    new WindowsSandboxWorker.StreamFileConsumer(chunkFile);
            byte[] utf8 = "你a".getBytes(StandardCharsets.UTF_8);
            writer.accept(java.util.Arrays.copyOfRange(utf8, 0, 2));
            writer.accept(java.util.Arrays.copyOfRange(utf8, 2, utf8.length));
            writer.throwIfFailed();
            complete.set(true);
            reader.get();

            assertEquals("你a", String.join("", textChunks));
        } finally {
            complete.set(true);
            readers.shutdownNow();
        }
    }
}
