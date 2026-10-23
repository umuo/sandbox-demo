package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.platform.win32.Advapi32Util;
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SANDBOX_WINDOWS_DEFAULT_ACCEPTANCE", matches = "1")
class WindowsDefaultAcceptanceTest {
    @TempDir Path root;

    private SandboxRequest.Builder request(Path workspace, String script) {
        return SandboxRequest.builder(
                        workspace,
                        Paths.get(System.getenv("SystemRoot"), "System32", "cmd.exe").toString())
                .arguments("/d", "/s", "/c", script)
                .timeout(Duration.ofSeconds(30));
    }

    @Test
    void acceptanceUsesRealStandardUserAndDefaultBackend() {
        assertFalse(Advapi32Util.isCurrentProcessElevated());
        for (Advapi32Util.Account group : Advapi32Util.getCurrentUserGroups()) {
            assertNotEquals(
                    "S-1-5-32-544", group.sidString, "test user must not be an administrator");
        }
        assertEquals("windows-restricted-token", SandboxClient.create().backendName());
    }

    @Test
    void unsupportedNetworkPolicyFailsBeforeTargetStarts() throws Exception {
        SandboxPolicyReadiness readiness =
                SandboxClient.create()
                        .readiness(
                                request(root, "echo unexpected>target-marker")
                                        .network(NetworkPolicy.DENY)
                                        .build());
        assertFalse(readiness.ready());
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, readiness.error().get().code());
        assertEquals(SandboxPhase.ADMISSION, readiness.error().get().phase());
        assertEquals(SandboxCleanupState.NOT_REQUIRED, readiness.cleanup().processes());
        assertFalse(Files.exists(root.resolve("target-marker")));
    }

    @Test
    void privateDirectoryUsesTokenIdentityWhenUserNameIsSpoofed() throws Exception {
        String previous = System.getProperty("user.name");
        Path directory = null;
        UserPrincipal expected =
                root.getFileSystem()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(Advapi32Util.getUserName());
        try {
            System.setProperty("user.name", "SYSTEM");
            directory = io.github.sandboxdemo.core.PrivateDirectories.create(root, "identity-");
            List<AclEntry> acl =
                    Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl();
            assertFalse(acl.isEmpty());
            for (AclEntry entry : acl) {
                if (entry.type() == AclEntryType.ALLOW) {
                    assertEquals(expected, entry.principal(), "DACL must name the real token user");
                }
            }
            Path canary = Files.write(directory.resolve("canary"), new byte[] {42});
            assertArrayEquals(new byte[] {42}, Files.readAllBytes(canary));
            Files.delete(canary);
        } finally {
            if (previous == null) {
                System.clearProperty("user.name");
            } else {
                System.setProperty("user.name", previous);
            }
            if (directory != null) {
                // Restore synthetic permissions even if the regression returns an incorrect DACL.
                AclEntry owner =
                        AclEntry.newBuilder()
                                .setType(AclEntryType.ALLOW)
                                .setPrincipal(expected)
                                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                                .build();
                Files.getFileAttributeView(directory, AclFileAttributeView.class)
                        .setAcl(Collections.singletonList(owner));
                assertTrue(io.github.sandboxdemo.core.PrivateDirectories.removeTree(directory));
            }
        }
    }

    @Test
    void concurrentExecutionsKeepWorkspacesAndCleanupIndependent() throws Exception {
        SandboxClient client = SandboxClient.create();
        ThreadPoolExecutor workers =
                new ThreadPoolExecutor(
                        4,
                        4,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<Runnable>(4),
                        new ThreadPoolExecutor.AbortPolicy());
        List<SandboxExecution> executions = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                Path workspace = Files.createDirectory(root.resolve("work-" + i));
                executions.add(
                        client.start(
                                request(
                                                workspace,
                                                ">own-marker echo value-"
                                                        + i
                                                        + " & type own-marker")
                                        .build(),
                                workers));
            }
            for (int i = 0; i < executions.size(); i++) {
                SandboxCompletion completion = executions.get(i).await(Duration.ofSeconds(35));
                assertEquals(
                        SandboxTerminationReason.EXITED,
                        completion.reason(),
                        completion.toString());
                assertTrue(completion.result().get().completeSuccessful());
                assertEquals("value-" + i, completion.result().get().stdoutUtf8().trim());
                assertEquals(SandboxCleanupState.CONFIRMED, completion.cleanup().processes());
                assertEquals(SandboxCleanupState.CONFIRMED, completion.cleanup().temporaryFiles());
                assertEquals(SandboxCleanupState.UNKNOWN, completion.cleanup().hostResources());
                assertFalse(completion.completeSuccessful());
            }
        } finally {
            for (SandboxExecution execution : executions) {
                execution.cancel();
            }
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
