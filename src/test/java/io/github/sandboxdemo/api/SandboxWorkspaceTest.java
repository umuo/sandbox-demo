package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

class SandboxWorkspaceTest {
    @TempDir Path parent;

    @Test
    void suppliesIndependentDirectoriesAndReportsCleanup() throws Exception {
        SandboxWorkspace one = SandboxWorkspace.create(parent);
        SandboxWorkspace two = SandboxWorkspace.create(parent);
        try {
            assertNotEquals(one.directory(), two.directory());
            assertNotEquals(one.temporaryParent(), two.temporaryParent());
            assertFalse(one.directory().startsWith(one.temporaryParent()));
            assertTrue(one.request("synthetic").build().privateTempParent().isPresent());
            assertEquals(SandboxCleanupState.UNKNOWN, one.cleanupState());
            assertFalse(one.toString().contains(one.directory().toString()));
            Files.write(one.directory().resolve("synthetic"), new byte[] {1});
        } finally {
            one.close();
            two.close();
        }
        assertEquals(SandboxCleanupState.CONFIRMED, one.cleanupState());
        assertEquals(SandboxCleanupState.CONFIRMED, two.cleanupState());
        assertFalse(Files.exists(one.directory()));
        assertThrows(IllegalStateException.class, () -> one.request("synthetic"));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void cleanupDoesNotFollowLinksToExternalContents() throws Exception {
        Path outside = Files.createDirectory(parent.resolve("outside"));
        Path canary = Files.write(outside.resolve("canary"), new byte[] {42});
        SandboxWorkspace workspace = SandboxWorkspace.create(parent);
        Files.createSymbolicLink(workspace.directory().resolve("external"), outside);
        assertEquals(SandboxCleanupState.CONFIRMED, workspace.cleanup());
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(canary));
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void cleanupFailureIsVisibleAndRetryable() throws Exception {
        SandboxWorkspace workspace = SandboxWorkspace.create(parent);
        Path work = workspace.directory();
        Files.write(work.resolve("canary"), new byte[] {1});
        Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("r-x------"));
        try {
            Assumptions.assumeFalse(
                    Files.isWritable(work), "privileged host bypasses directory permissions");
            assertEquals(SandboxCleanupState.FAILED, workspace.cleanup());
            assertEquals(SandboxCleanupState.FAILED, workspace.cleanupState());
            assertTrue(Files.exists(work.resolve("canary")));
        } finally {
            Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------"));
            assertEquals(SandboxCleanupState.CONFIRMED, workspace.cleanup());
        }
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void privateDirectoriesStartWithOwnerOnlyPermissions() throws Exception {
        try (SandboxWorkspace workspace = SandboxWorkspace.create(parent)) {
            assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(workspace.directory()));
            assertEquals(
                    PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(workspace.temporaryParent()));
        }
    }

    @Test
    void replacedWorkspaceRootIsNotDeletedAndReportsUnknown() throws Exception {
        SandboxWorkspace workspace = SandboxWorkspace.create(parent);
        Path original = workspace.directory().getParent();
        Path moved = parent.resolve("saved-root");
        Files.move(original, moved);
        Files.createDirectory(original);
        try {
            assertEquals(SandboxCleanupState.UNKNOWN, workspace.cleanup());
            assertTrue(Files.isDirectory(original));
            assertTrue(Files.isDirectory(moved));
        } finally {
            Files.delete(original);
            Files.move(moved, original);
            assertEquals(SandboxCleanupState.CONFIRMED, workspace.cleanup());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionCleanupPreservesExternalCanary() throws Exception {
        Path outside = Files.createDirectory(parent.resolve("outside"));
        Path canary = Files.write(outside.resolve("canary"), new byte[] {42});
        try (SandboxWorkspace workspace = SandboxWorkspace.create(parent)) {
            Path link = workspace.directory().resolve("junction");
            String cmd =
                    java.nio.file.Paths.get(
                                    System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                                    "System32",
                                    "cmd.exe")
                            .toString();
            Process process =
                    new ProcessBuilder(
                                    cmd,
                                    "/d",
                                    "/s",
                                    "/c",
                                    "mklink /J \"" + link + "\" \"" + outside + "\"")
                            .redirectErrorStream(true)
                            .start();
            try {
                assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), "junction fixture creation failed");
                assertTrue(io.github.sandboxdemo.core.PrivateDirectories.isLink(link));
                assertEquals(SandboxCleanupState.CONFIRMED, workspace.cleanup());
                assertArrayEquals(new byte[] {42}, Files.readAllBytes(canary));
            } finally {
                process.destroyForcibly();
            }
        }
    }
}
