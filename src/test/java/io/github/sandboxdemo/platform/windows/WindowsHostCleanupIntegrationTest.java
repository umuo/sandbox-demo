package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

/** Real native cleanup and safe acknowledgement faults on both CI identities/backends. */
@EnabledOnOs(OS.WINDOWS)
@EnabledIf("io.github.sandboxdemo.platform.windows.WindowsNativeTestSupport#enabled")
class WindowsHostCleanupIntegrationTest {
    @TempDir Path workspace;

    private SandboxRunner runner() {
        return WindowsNativeTestSupport.defaultBackend()
                ? new WindowsRestrictedTokenSandboxRunner()
                : new WindowsProductionSandboxRunner(WindowsSandboxInstallation.defaultHome());
    }

    private SandboxRequest request() {
        return SandboxRequest.builder(
                        workspace,
                        Paths.get(
                                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                                        "System32",
                                        "cmd.exe")
                                .toString())
                .arguments("/d", "/s", "/c", "echo started>marker")
                .readPolicy(ReadPolicy.HOST)
                .network(NetworkPolicy.ALLOW)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private ExecutionContext context() {
        ExecutionContext context =
                new ExecutionContext(Duration.ofSeconds(120), Duration.ofSeconds(2));
        assertTrue(context.beginWorker());
        return context;
    }

    private void confirmed(SandboxCleanupResult cleanup, SandboxHostResource... resources) {
        for (SandboxHostResource resource : resources) {
            assertEquals(
                    SandboxCleanupState.CONFIRMED,
                    cleanup.hostDetails().get(resource),
                    () -> resource + ": " + cleanup.hostDetails());
        }
    }

    private void record(String scenario, SandboxCleanupResult cleanup) throws Exception {
        Properties evidence = new Properties();
        evidence.setProperty("backend", runner().backendName());
        evidence.setProperty("host", cleanup.hostResources().name());
        cleanup.hostDetails()
                .forEach((key, value) -> evidence.setProperty(key.name(), value.name()));
        Path directory = Paths.get("target", "cleanup-evidence");
        Files.createDirectories(directory);
        try (java.io.OutputStream out =
                Files.newOutputStream(directory.resolve(scenario + ".xml"))) {
            evidence.storeToXML(
                    out, "Observed host cleanup checks; absent entries are unobserved", "UTF-8");
        }
    }

    @Test
    void successfulExecutionReportsCheckedHostResourcesWithoutPromotingUnknown() throws Exception {
        ExecutionContext context = context(), previous = ExecutionContext.attach(context);
        try {
            assertTrue(runner().execute(request()).completeSuccessful());
        } finally {
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
        SandboxCleanupResult cleanup = context.cleanup();
        confirmed(
                cleanup,
                SandboxHostResource.NATIVE_HANDLES,
                SandboxHostResource.PATH_HANDLES,
                SandboxHostResource.FILESYSTEM_ACL,
                SandboxHostResource.NATIVE_ALLOCATIONS);
        if (WindowsNativeTestSupport.defaultBackend()) {
            confirmed(
                    cleanup,
                    SandboxHostResource.PRIVATE_DESKTOP,
                    SandboxHostResource.WINDOW_STATION_ACL);
        } else {
            confirmed(cleanup, SandboxHostResource.WORKER_SESSION);
            assertEquals(
                    SandboxCleanupState.UNKNOWN,
                    cleanup.hostDetails().get(SandboxHostResource.REMOTE_WORKER));
        }
        assertEquals(SandboxCleanupState.UNKNOWN, cleanup.hostResources());
        assertFalse(cleanup.confirmed());
        record("success", cleanup);
    }

    @Test
    void suspendedLaunchFailuresStillReleaseAclAndPathHandles() throws Exception {
        String prefix = WindowsNativeTestSupport.defaultBackend() ? "" : "worker-";
        for (String stage : Arrays.asList("create-job", "assign-job", "resume")) {
            ExecutionContext context = context(), previous = ExecutionContext.attach(context);
            try (AutoCloseable ignored =
                    WindowsCleanup.inject(value -> !value.equals(prefix + stage))) {
                assertThrows(SandboxException.class, () -> runner().execute(request()), stage);
            } finally {
                context.finishWorker();
                ExecutionContext.restore(previous);
            }
            assertFalse(Files.exists(workspace.resolve("marker")), stage);
            confirmed(
                    context.cleanup(),
                    SandboxHostResource.NATIVE_HANDLES,
                    SandboxHostResource.PATH_HANDLES,
                    SandboxHostResource.FILESYSTEM_ACL);
            assertNotEquals(SandboxCleanupState.FAILED, context.cleanup().processes());
            record(stage, context.cleanup());
        }
    }

    @Test
    void failedCleanupAcknowledgementRetainsFailureAndContinuesOtherCleanup() throws Exception {
        List<SandboxHostResource> resources =
                new ArrayList<>(
                        Arrays.asList(
                                SandboxHostResource.FILESYSTEM_ACL,
                                SandboxHostResource.PATH_HANDLES,
                                SandboxHostResource.NATIVE_HANDLES));
        if (!WindowsNativeTestSupport.defaultBackend())
            resources.add(SandboxHostResource.WORKER_SESSION);
        for (SandboxHostResource fault : resources) {
            ExecutionContext context = context(), previous = ExecutionContext.attach(context);
            try (AutoCloseable ignored =
                    WindowsCleanup.inject(value -> !value.equals(fault.name()))) {
                assertTrue(runner().execute(request()).completeSuccessful());
            } finally {
                context.finishWorker();
                ExecutionContext.restore(previous);
            }
            SandboxCleanupResult cleanup = context.cleanup();
            assertEquals(
                    SandboxCleanupState.FAILED,
                    cleanup.hostDetails().get(fault),
                    cleanup.hostDetails().toString());
            assertEquals(SandboxCleanupState.FAILED, cleanup.hostResources());
            if (fault != SandboxHostResource.FILESYSTEM_ACL)
                confirmed(cleanup, SandboxHostResource.FILESYSTEM_ACL);
            if (fault != SandboxHostResource.NATIVE_HANDLES)
                confirmed(cleanup, SandboxHostResource.NATIVE_HANDLES);
            // Real cleanup precedes the injected failure; a subsequent real execution must work.
            assertTrue(WindowsNativeTestSupport.client().execute(request()).completeSuccessful());
            record("fault-" + fault, cleanup);
        }
    }

    private byte[] acl(Path path) throws Exception {
        PointerByReference dacl = new PointerByReference(), descriptor = new PointerByReference();
        assertEquals(
                0,
                WindowsNative.Advapi32.INSTANCE.GetNamedSecurityInfoW(
                        new WString(path.toString()), 1, 4, null, null, dacl, null, descriptor));
        try {
            return WindowsAclEvidence.bytes(dacl.getValue());
        } finally {
            WindowsCleanup.localFree(descriptor.getValue());
        }
    }

    @Test
    void protectedDescendantRestoresOriginalDaclWithoutTemporaryCapabilitySid() throws Exception {
        Path protectedFile = Files.write(workspace.resolve("protected"), new byte[] {1});
        byte[] rootBefore = acl(workspace), protectedBefore = acl(protectedFile);
        ValidatedPolicy policy =
                PathPolicyValidator.validate(
                        SandboxRequest.builder(workspace, request().command())
                                .protect(protectedFile)
                                .readPolicy(ReadPolicy.HOST)
                                .network(NetworkPolicy.ALLOW)
                                .build());
        ExecutionContext context = context(), previous = ExecutionContext.attach(context);
        List<String> sids;
        try {
            WindowsAclManager.WindowsAclLease lease = new WindowsAclManager().apply(policy);
            sids = new ArrayList<>(lease.capabilitySids());
            lease.close();
            lease.close();
            assertArrayEquals(rootBefore, acl(workspace));
            assertArrayEquals(protectedBefore, acl(protectedFile));
            for (String sid : sids) {
                assertTrue(WindowsAclEvidence.named(workspace, null, null, sid));
                assertTrue(WindowsAclEvidence.named(protectedFile, null, null, sid));
            }
            confirmed(context.cleanup(), SandboxHostResource.FILESYSTEM_ACL);
        } finally {
            PathPolicyValidator.cleanup(policy);
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
        record("protected-descendant", context.cleanup());
    }

    @Test
    void failedAclPreparationRestoresOriginalDacl() throws Exception {
        byte[] before = acl(workspace);
        ValidatedPolicy policy = PathPolicyValidator.validate(request());
        ExecutionContext context = context(), previous = ExecutionContext.attach(context);
        try (AutoCloseable ignored = WindowsCleanup.inject(value -> !value.equals("acl-applied"))) {
            assertThrows(SandboxException.class, () -> new WindowsAclManager().apply(policy));
            assertArrayEquals(before, acl(workspace));
            confirmed(context.cleanup(), SandboxHostResource.FILESYSTEM_ACL);
        } finally {
            PathPolicyValidator.cleanup(policy);
            context.finishWorker();
            ExecutionContext.restore(previous);
        }
        record("acl-preparation", context.cleanup());
    }

    @Test
    void desktopCreationAndCloseFailuresStillRevokeWindowStationCapability() throws Exception {
        com.sun.jna.platform.win32.WinNT.HANDLEByReference token =
                new com.sun.jna.platform.win32.WinNT.HANDLEByReference();
        assertTrue(
                com.sun.jna.platform.win32.Advapi32.INSTANCE.OpenProcessToken(
                        com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess(),
                        com.sun.jna.platform.win32.WinNT.TOKEN_QUERY,
                        token));
        String user;
        try {
            user =
                    com.sun.jna.platform.win32.Advapi32Util.getTokenAccount(token.getValue())
                            .sidString;
        } finally {
            assertTrue(WindowsCleanup.closeHandle(token.getValue().getPointer()));
        }
        for (String fault :
                Arrays.asList("create-desktop", "PRIVATE_DESKTOP", "WINDOW_STATION_ACL")) {
            String capability = WindowsCapabilitySid.random();
            ExecutionContext context = context(), previous = ExecutionContext.attach(context);
            try (AutoCloseable ignored = WindowsCleanup.inject(value -> !value.equals(fault))) {
                if (fault.equals("create-desktop")) {
                    assertThrows(
                            SandboxException.class,
                            () ->
                                    WindowsPrivateDesktop.create(
                                            user, Collections.singletonList(capability)));
                } else {
                    WindowsPrivateDesktop desktop =
                            WindowsPrivateDesktop.create(
                                    user, Collections.singletonList(capability));
                    desktop.close();
                    desktop.close();
                    assertEquals(
                            SandboxCleanupState.FAILED,
                            context.cleanup()
                                    .hostDetails()
                                    .get(SandboxHostResource.valueOf(fault)));
                }
                Pointer station = WindowsNative.User32.INSTANCE.GetProcessWindowStation();
                assertTrue(WindowsAclEvidence.station(station, capability));
                if (!fault.equals("WINDOW_STATION_ACL"))
                    confirmed(context.cleanup(), SandboxHostResource.WINDOW_STATION_ACL);
                if (!fault.equals("PRIVATE_DESKTOP"))
                    confirmed(context.cleanup(), SandboxHostResource.PRIVATE_DESKTOP);
            } finally {
                context.finishWorker();
                ExecutionContext.restore(previous);
            }
            record("desktop-" + fault, context.cleanup());
        }
    }
}
