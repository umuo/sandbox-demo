package io.github.sandboxdemo.api;

import io.github.sandboxdemo.core.PrivateDirectories;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** 宿主供应的独立工作区，不接收模型根目录配置。宿主保证父目录可信并在后台执行结束后清理。 不跟随链接删除；不提供输入复制、硬链接清洗或抵御不可信父目录重命名的保证。 */
public final class SandboxWorkspace implements AutoCloseable {
    private final Path root, directory, temporaryParent;
    private final Object rootKey;
    private SandboxCleanupState cleanup = SandboxCleanupState.UNKNOWN;
    private boolean cleanupRequested;

    private SandboxWorkspace(Path root) throws IOException {
        this.root = root;
        this.rootKey =
                Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .fileKey();
        this.directory = PrivateDirectories.create(root, "work-");
        this.temporaryParent = PrivateDirectories.create(root, "temp-");
    }

    public static SandboxWorkspace create(Path trustedParent) throws SandboxException {
        Path root = null;
        try {
            root =
                    PrivateDirectories.create(
                            Objects.requireNonNull(trustedParent, "trustedParent"),
                            "sandbox-workspace-");
            return new SandboxWorkspace(root);
        } catch (IOException | RuntimeException failure) {
            if (root != null && !remove(root)) {
                throw new SandboxException(
                        SandboxErrorCode.CLEANUP_FAILED,
                        SandboxPhase.CLEANUP,
                        "workspace preparation rollback failed: " + root,
                        failure);
            }
            throw new SandboxException(
                    SandboxErrorCode.POLICY_INVALID,
                    SandboxPhase.PREPARATION,
                    "failed to supply private workspace",
                    failure);
        }
    }

    /** 仅供宿主供应输入或读取产物；默认字符串不输出路径。 */
    public Path directory() {
        return directory;
    }

    public Path temporaryParent() {
        return temporaryParent;
    }

    public synchronized SandboxRequest.Builder request(String executable) {
        if (cleanupRequested) {
            throw new IllegalStateException("workspace cleanup has already been requested");
        }
        return SandboxRequest.builder(directory, executable).privateTempParent(temporaryParent);
    }

    /** 清理为同步尽力操作，FAILED 可重试；调用前必须确认不再有后台执行使用该目录。 */
    public synchronized SandboxCleanupState cleanup() {
        cleanupRequested = true;
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                cleanup = SandboxCleanupState.CONFIRMED;
            } else if (Files.isSymbolicLink(root)
                    || rootKey == null
                    || !rootKey.equals(
                            Files.readAttributes(
                                            root,
                                            BasicFileAttributes.class,
                                            LinkOption.NOFOLLOW_LINKS)
                                    .fileKey())) {
                cleanup = SandboxCleanupState.UNKNOWN;
            } else {
                cleanup = remove(root) ? SandboxCleanupState.CONFIRMED : SandboxCleanupState.FAILED;
            }
        } catch (IOException | RuntimeException failure) {
            cleanup = SandboxCleanupState.FAILED;
        }
        return cleanup;
    }

    public synchronized SandboxCleanupState cleanupState() {
        return cleanup;
    }

    @Override
    public void close() {
        cleanup();
    }

    @Override
    public synchronized String toString() {
        return "SandboxWorkspace[cleanup=" + cleanup + "]";
    }

    private static boolean remove(Path root) {
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                                throws IOException {
                            Files.delete(path);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path path, IOException error)
                                throws IOException {
                            if (error != null) {
                                throw error;
                            }
                            Files.delete(path);
                            return FileVisitResult.CONTINUE;
                        }
                    });
            return Files.notExists(root, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }
}
