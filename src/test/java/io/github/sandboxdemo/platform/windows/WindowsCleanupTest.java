package io.github.sandboxdemo.platform.windows;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Memory;
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.ExecutionContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WindowsCleanupTest {
    @Test
    void namedChecksRetainCapacityUntilFinishedAndFailureCannotBeOverwritten() {
        AtomicInteger releases = new AtomicInteger();
        ExecutionContext context =
                new ExecutionContext(
                        Duration.ofSeconds(10), Duration.ofSeconds(1), releases::incrementAndGet);
        ExecutionContext.HostLease first = context.trackHost(SandboxHostResource.PATH_HANDLES);
        ExecutionContext.HostLease second = context.trackHost(SandboxHostResource.PATH_HANDLES);
        context.finishWorker();
        assertEquals(
                SandboxCleanupState.UNKNOWN,
                context.cleanup().hostDetails().get(SandboxHostResource.PATH_HANDLES));
        first.complete(SandboxCleanupState.FAILED);
        assertEquals(0, releases.get());
        assertEquals(SandboxCleanupState.FAILED, context.cleanup().hostResources());
        second.complete(SandboxCleanupState.CONFIRMED);
        first.complete(SandboxCleanupState.CONFIRMED);
        assertEquals(1, releases.get());
        assertEquals(
                SandboxCleanupState.FAILED,
                context.cleanup().hostDetails().get(SandboxHostResource.PATH_HANDLES));
    }

    @Test
    void cleanupContinuesAfterExceptionsAndFaultsAcknowledgeRealCleanupFirst() throws Exception {
        ExecutionContext context =
                new ExecutionContext(Duration.ofSeconds(10), Duration.ofSeconds(1));
        ExecutionContext previous = ExecutionContext.attach(context);
        AtomicInteger closed = new AtomicInteger();
        try {
            try (AutoCloseable ignored =
                    WindowsCleanup.inject(stage -> !stage.equals("FILESYSTEM_ACL"))) {
                assertFalse(
                        WindowsCleanup.run(
                                SandboxHostResource.FILESYSTEM_ACL,
                                () -> {
                                    closed.incrementAndGet();
                                    return true;
                                }));
                assertFalse(
                        WindowsCleanup.run(
                                SandboxHostResource.PATH_HANDLES,
                                () -> {
                                    throw new IllegalStateException();
                                }));
                assertTrue(
                        WindowsCleanup.run(
                                SandboxHostResource.PRIVATE_DESKTOP,
                                () -> {
                                    closed.incrementAndGet();
                                    return true;
                                }));
            }
            assertTrue(WindowsCleanup.run(SandboxHostResource.FILESYSTEM_ACL, () -> true));
            assertEquals(2, closed.get());
            assertEquals(
                    SandboxCleanupState.FAILED,
                    context.cleanup().hostDetails().get(SandboxHostResource.FILESYSTEM_ACL));
            assertEquals(
                    SandboxCleanupState.CONFIRMED,
                    context.cleanup().hostDetails().get(SandboxHostResource.PRIVATE_DESKTOP));
        } finally {
            ExecutionContext.restore(previous);
            context.finishWorker();
        }
        assertNull(ExecutionContext.current());
    }

    @Test
    void detailedResultCopiesAndDowngradesEvidenceWithoutPromotingUnknown() {
        Map<SandboxHostResource, SandboxCleanupState> details =
                new EnumMap<>(SandboxHostResource.class);
        details.put(SandboxHostResource.FILESYSTEM_ACL, SandboxCleanupState.CONFIRMED);
        SandboxCleanupResult result = result(SandboxCleanupState.UNKNOWN, details);
        details.clear();
        assertEquals(1, result.hostDetails().size());
        assertFalse(result.confirmed());
        assertThrows(UnsupportedOperationException.class, () -> result.hostDetails().clear());
        details.put(SandboxHostResource.FILESYSTEM_ACL, SandboxCleanupState.FAILED);
        assertEquals(
                SandboxCleanupState.FAILED,
                result(SandboxCleanupState.CONFIRMED, details).hostResources());
        details.put(SandboxHostResource.FILESYSTEM_ACL, SandboxCleanupState.UNKNOWN);
        assertFalse(result(SandboxCleanupState.CONFIRMED, details).confirmed());
        assertTrue(
                new SandboxCleanupResult(
                                SandboxCleanupState.NOT_REQUIRED,
                                SandboxCleanupState.NOT_REQUIRED,
                                SandboxCleanupState.NOT_REQUIRED,
                                SandboxCleanupState.NOT_REQUIRED,
                                true)
                        .hostDetails()
                        .isEmpty());
        ExecutionContext context =
                new ExecutionContext(Duration.ofSeconds(10), Duration.ofSeconds(1));
        context.reportCleanup(result(SandboxCleanupState.UNKNOWN, details));
        context.trackHost(SandboxHostResource.FILESYSTEM_ACL)
                .complete(SandboxCleanupState.CONFIRMED);
        assertEquals(
                SandboxCleanupState.UNKNOWN,
                context.cleanup().hostDetails().get(SandboxHostResource.FILESYSTEM_ACL));
    }

    private SandboxCleanupResult result(
            SandboxCleanupState aggregate, Map<SandboxHostResource, SandboxCleanupState> details) {
        return new SandboxCleanupResult(
                SandboxCleanupState.NOT_REQUIRED,
                SandboxCleanupState.NOT_REQUIRED,
                SandboxCleanupState.NOT_REQUIRED,
                aggregate,
                true,
                details);
    }

    @Test
    void aclEvidenceIgnoresUnusedPaddingButPreservesAceBytesAndChecksSids() {
        Memory acl = new Memory(40);
        acl.clear();
        acl.setByte(0, (byte) 2);
        acl.setShort(2, (short) 40);
        acl.setShort(4, (short) 1);
        acl.setShort(10, (short) 20); // ACCESS_ALLOWED_ACE with S-1-5-32
        acl.setByte(16, (byte) 1);
        acl.setByte(17, (byte) 1);
        acl.setByte(23, (byte) 5);
        acl.setInt(24, 32);
        byte[] original = WindowsAclEvidence.bytes(acl);
        acl.setByte(35, (byte) 99);
        assertArrayEquals(original, WindowsAclEvidence.bytes(acl));
        assertTrue(WindowsAclEvidence.containsSid(acl, "S-1-5-32"));
        assertFalse(WindowsAclEvidence.containsSid(acl, "S-1-5-33"));
        acl.setByte(9, (byte) 16); // inheritance flag is material
        assertFalse(Arrays.equals(original, WindowsAclEvidence.bytes(acl)));
        acl.setShort(10, (short) 100);
        assertThrows(IllegalArgumentException.class, () -> WindowsAclEvidence.bytes(acl));
    }
}
