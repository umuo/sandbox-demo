package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxCapabilityDimensionsTest {
    @Test
    void windowsDoesNotAdvertiseStrictReadOrDefaultNetworkDenial() {
        SandboxCapabilities defaults =
                SandboxRuntime.capabilities(SandboxPlatform.WINDOWS, "windows-restricted-token");
        assertEquals(
                SandboxGuarantee.UNSUPPORTED,
                defaults.support(SandboxCapability.STRICT_READ).guarantee());
        assertEquals(
                SandboxGuarantee.UNSUPPORTED,
                defaults.support(SandboxCapability.NETWORK_DENY).guarantee());
        assertEquals(
                SandboxGuarantee.KERNEL,
                defaults.support(SandboxCapability.PROCESS_TREE_TERMINATION).guarantee());
        assertEquals(
                SandboxGuarantee.KERNEL,
                defaults.support(SandboxCapability.MEMORY_LIMIT).guarantee());
        SandboxCapabilities dedicated =
                SandboxRuntime.capabilities(
                        SandboxPlatform.WINDOWS, "windows-dedicated-user-restricted-token");
        assertEquals(
                SandboxGuarantee.KERNEL,
                dedicated.support(SandboxCapability.NETWORK_DENY).guarantee());
        assertEquals(
                SandboxGuarantee.UNSUPPORTED,
                dedicated.support(SandboxCapability.STRICT_READ).guarantee());
    }

    @Test
    void macOsDoesNotClaimHardResourcesOrCompleteDescendantSupervision() {
        SandboxCapabilities mac =
                SandboxRuntime.capabilities(SandboxPlatform.MACOS, "macos-seatbelt");
        assertEquals(
                SandboxGuarantee.BEST_EFFORT,
                mac.support(SandboxCapability.PROCESS_TREE_TERMINATION).guarantee());
        assertEquals(
                SandboxGuarantee.UNSUPPORTED,
                mac.support(SandboxCapability.MEMORY_LIMIT).guarantee());
        assertEquals(
                SandboxGuarantee.BEST_EFFORT,
                mac.support(SandboxCapability.TEMP_CLEANUP).guarantee());
        SandboxCapabilities linux =
                SandboxRuntime.capabilities(SandboxPlatform.LINUX, "linux-bubblewrap");
        assertEquals(
                SandboxGuarantee.KERNEL,
                linux.support(SandboxCapability.PROCESS_TREE_TERMINATION).guarantee());
        assertEquals(
                SandboxGuarantee.UNSUPPORTED,
                linux.support(SandboxCapability.CPU_LIMIT).guarantee());
    }

    @Test
    void legacyAndCustomBackendsDoNotInheritUnprovenGuarantees() {
        SandboxCapabilities legacy =
                new SandboxCapabilities(
                        SandboxPlatform.MACOS,
                        "custom",
                        Collections.singleton(ReadPolicy.HOST),
                        Collections.singleton(NetworkPolicy.ALLOW),
                        false);
        for (SandboxCapability capability : SandboxCapability.values()) {
            assertEquals(SandboxGuarantee.UNKNOWN, legacy.support(capability).guarantee());
        }
        assertEquals(
                SandboxGuarantee.UNKNOWN,
                SandboxRuntime.capabilities(SandboxPlatform.MACOS, "custom")
                        .support(SandboxCapability.STRICT_READ)
                        .guarantee());
    }

    @Test
    void capabilityDimensionsAreDefensivelyCopiedAndAffectValueIdentity() {
        Map<SandboxCapability, SandboxCapabilitySupport> dimensions =
                new EnumMap<>(SandboxCapability.class);
        dimensions.put(
                SandboxCapability.STRICT_READ,
                new SandboxCapabilitySupport(SandboxGuarantee.UNSUPPORTED, "none"));
        SandboxCapabilities explicit =
                new SandboxCapabilities(
                        SandboxPlatform.MACOS,
                        "custom",
                        Collections.singleton(ReadPolicy.HOST),
                        Collections.singleton(NetworkPolicy.ALLOW),
                        Collections.singleton(DeletionPolicy.ALLOW),
                        false,
                        SandboxEnforcement.PARTIAL,
                        dimensions);
        dimensions.clear();
        assertEquals(
                SandboxGuarantee.UNSUPPORTED,
                explicit.support(SandboxCapability.STRICT_READ).guarantee());
        assertThrows(UnsupportedOperationException.class, () -> explicit.dimensions().clear());
        SandboxCapabilities legacy =
                new SandboxCapabilities(
                        SandboxPlatform.MACOS,
                        "custom",
                        Collections.singleton(ReadPolicy.HOST),
                        Collections.singleton(NetworkPolicy.ALLOW),
                        false,
                        SandboxEnforcement.PARTIAL);
        assertNotEquals(legacy, explicit);
        SandboxRuntimeStatus status = new SandboxRuntimeStatus(explicit, false, "synthetic-secret");
        assertFalse(status.toString().contains("synthetic-secret"));
    }
}
