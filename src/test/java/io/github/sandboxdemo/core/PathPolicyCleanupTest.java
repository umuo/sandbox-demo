package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** 使用 SDK 自有合成目录复现删除失败，不修改宿主账户或业务文件。 */
@EnabledOnOs({OS.MAC, OS.LINUX})
class PathPolicyCleanupTest {
    @TempDir Path workspace;

    @Test
    void deletionFailureIsObservableAndDoesNotBecomeConfirmed() throws Exception {
        ExecutionContext context =
                new ExecutionContext(Duration.ofSeconds(2), Duration.ofMillis(100));
        context.beginWorker();
        context.managedBackend();
        ExecutionContext previous = ExecutionContext.attach(context);
        ValidatedPolicy policy =
                PathPolicyValidator.validate(SandboxPolicy.builder(workspace).build());
        Path directory = policy.privateTempDirectory();
        try {
            Files.write(directory.resolve("synthetic-canary"), new byte[] {1});
            Files.setPosixFilePermissions(
                    directory,
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            Assumptions.assumeFalse(
                    Files.isWritable(directory),
                    "privileged filesystem ignores owner write restriction");
            assertEquals(SandboxCleanupState.FAILED, PathPolicyValidator.cleanupWithResult(policy));
            assertEquals(SandboxCleanupState.FAILED, context.cleanup().temporaryFiles());
            assertTrue(Files.exists(directory));
        } finally {
            Files.setPosixFilePermissions(
                    directory,
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
            PathPolicyValidator.cleanup(policy);
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
        assertFalse(context.cleanup().confirmed());
        assertFalse(Files.exists(directory));
    }
}
