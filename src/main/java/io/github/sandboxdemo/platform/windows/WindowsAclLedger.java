package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.api.SandboxException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Crash-safe inventory of persistent dedicated-user workspace ACEs. */
final class WindowsAclLedger {

    private static final String FILE_NAME = "workspace-acls.tsv";
    private final Path file;

    WindowsAclLedger(Path home) {
        this.file = home.resolve(FILE_NAME);
    }

    void record(Path path, String sid) throws SandboxException {
        String entry = encode(path.toAbsolutePath().normalize().toString()) + '\t' + sid;
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel =
                    FileChannel.open(
                            file,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE)) {
                try (java.nio.channels.FileLock ignored = channel.lock()) {
                    byte[] current = new byte[(int) channel.size()];
                    channel.position(0);
                    ByteBuffer currentBuffer = ByteBuffer.wrap(current);
                    while (currentBuffer.hasRemaining()) {
                        if (channel.read(currentBuffer) < 0) {
                            break;
                        }
                    }
                    Set<String> entries =
                            new LinkedHashSet<>(
                                    io.github.sandboxdemo.core.Java8.listOf(
                                            new String(current, StandardCharsets.UTF_8)
                                                    .split("\\R")));
                    entries.remove("");
                    if (entries.add(entry)) {
                        String updated =
                                String.join(System.lineSeparator(), entries)
                                        + System.lineSeparator();
                        byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
                        channel.truncate(0);
                        channel.position(0);
                        ByteBuffer updatedBuffer = ByteBuffer.wrap(bytes);
                        while (updatedBuffer.hasRemaining()) {
                            channel.write(updatedBuffer);
                        }
                        channel.force(true);
                    }
                }
            }
        } catch (IOException e) {
            throw new SandboxException("failed to record Windows workspace ACL metadata", e);
        }
    }

    List<Entry> entries() throws SandboxException {
        if (!Files.isRegularFile(file)) {
            return io.github.sandboxdemo.core.Java8.listOf();
        }
        try {
            java.util.ArrayList<Entry> result = new java.util.ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (io.github.sandboxdemo.core.Java8.isBlank(line)) {
                    continue;
                }
                String[] parts = line.split("\\t", 2);
                if (parts.length != 2) {
                    throw new SandboxException("invalid Windows workspace ACL ledger entry");
                }
                result.add(new Entry(java.nio.file.Paths.get(decode(parts[0])), parts[1]));
            }
            return io.github.sandboxdemo.core.Java8.copyList(result);
        } catch (IOException | IllegalArgumentException e) {
            throw new SandboxException("failed to read Windows workspace ACL metadata", e);
        }
    }

    void delete() throws SandboxException {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new SandboxException("failed to remove Windows workspace ACL metadata", e);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static final class Entry {

        private final Path path;
        private final String sid;

        Entry(Path path, String sid) {
            this.path = path;
            this.sid = sid;
        }

        Path path() {
            return path;
        }

        String sid() {
            return sid;
        }
    }
}
