package io.github.sandboxdemo.sdk;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.ResourceAdmission;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

class SandboxResourceAdmissionTest {
    @TempDir Path workspace;

    private SandboxRequest request(SandboxResourceLimits limits) {
        return SandboxRequest.builder(workspace, "synthetic").resourceLimits(limits).build();
    }

    @Test
    void legacyBackendDoesNotGainHardLimitsFromCoarseCapabilities() throws Exception {
        AtomicInteger started = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "legacy-full";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        started.incrementAndGet();
                        return null;
                    }

                    public void validate(SandboxRequest request) {
                        started.incrementAndGet();
                    }
                };
        SandboxCapabilities caps =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        runner.backendName(),
                        EnumSet.allOf(ReadPolicy.class),
                        EnumSet.allOf(NetworkPolicy.class),
                        false);
        SandboxClient client =
                SandboxClient.builder()
                        .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, ""))
                        .build();
        for (SandboxResourceLimits limits :
                Arrays.asList(
                        SandboxResourceLimits.builder().memoryBytes(1024).build(),
                        SandboxResourceLimits.builder().processes(1).build(),
                        SandboxResourceLimits.builder().cpuTime(Duration.ofMillis(1)).build(),
                        SandboxResourceLimits.builder().diskBytes(1024).build())) {
            SandboxPolicyReadiness check = client.readiness(request(limits));
            assertFalse(check.ready());
            assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, check.error().get().code());
            assertEquals(SandboxPhase.ADMISSION, check.error().get().phase());
            assertEquals(SandboxCleanupState.NOT_REQUIRED, check.cleanup().processes());
            assertEquals(limits, check.resourceLimits());
            assertThrows(SandboxException.class, () -> client.execute(request(limits)));
        }
        assertEquals(0, started.get());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void realUnsupportedBackendRejectsEachLimitAndVmBeforePreparingResources() throws Exception {
        SandboxClient client = SandboxClient.create();
        for (SandboxResourceLimits limits :
                Arrays.asList(
                        SandboxResourceLimits.builder().memoryBytes(1024).build(),
                        SandboxResourceLimits.builder().processes(1).build(),
                        SandboxResourceLimits.builder().cpuTime(Duration.ofSeconds(1)).build(),
                        SandboxResourceLimits.builder().diskBytes(1024).build())) {
            SandboxPolicyReadiness check = client.readiness(request(limits));
            assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, check.error().get().code());
            assertEquals(SandboxCleanupState.NOT_REQUIRED, check.cleanup().temporaryFiles());
        }
        SandboxPolicyReadiness check =
                client.readiness(
                        SandboxRequest.builder(workspace, "synthetic")
                                .requiredIsolation(SandboxIsolationLevel.VIRTUAL_MACHINE)
                                .build());
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, check.error().get().code());
        assertEquals(SandboxIsolationLevel.OS_PROCESS, client.isolationLevel());
    }

    @Test
    void customHostBackendMustExplicitlyDeclareResourcesAndActualIsolation() throws Exception {
        SandboxPolicy policy =
                SandboxPolicy.builder(workspace)
                        .resourceLimits(
                                SandboxResourceLimits.builder()
                                        .memoryBytes(1024)
                                        .processes(2)
                                        .build())
                        .requiredIsolation(SandboxIsolationLevel.VIRTUAL_MACHINE)
                        .build();
        SandboxRunner host =
                new SandboxRunner() {
                    public String backendName() {
                        return "synthetic-host-vm-contract";
                    }

                    public SandboxIsolationLevel isolationLevel() {
                        return SandboxIsolationLevel.VIRTUAL_MACHINE;
                    }

                    public boolean supportsResourceLimits(SandboxResourceLimits limits) {
                        return limits.cpuTimeMillis() == 0 && limits.diskBytes() == 0;
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("contract test only");
                    }
                };
        ResourceAdmission.validate(policy, host);
        assertThrows(
                SandboxException.class,
                () ->
                        ResourceAdmission.validate(
                                SandboxPolicy.builder(workspace)
                                        .resourceLimits(
                                                SandboxResourceLimits.builder()
                                                        .diskBytes(1024)
                                                        .build())
                                        .build(),
                                host));
        assertTrue(
                SandboxIsolationLevel.VIRTUAL_MACHINE.satisfies(SandboxIsolationLevel.OS_PROCESS));
        assertFalse(SandboxIsolationLevel.UNKNOWN.satisfies(SandboxIsolationLevel.OS_PROCESS));
        // This is SPI admission verification, not evidence of an actual deployed VM.
    }

    @Test
    void limitsRejectInvalidInputsAndKeepLegacyDefaults() {
        assertTrue(SandboxPolicy.builder(workspace).build().resourceLimits().isEmpty());
        assertEquals(
                SandboxIsolationLevel.UNKNOWN,
                SandboxPolicy.builder(workspace).build().requiredIsolation());
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxResourceLimits.builder().memoryBytes(0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxResourceLimits.builder().processes(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxResourceLimits.builder().diskBytes(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxResourceLimits.builder().cpuTime(Duration.ofNanos(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxResourceLimits.builder().cpuTime(Duration.ofHours(25)));
        assertEquals(
                SandboxResourceLimits.builder().memoryBytes(5).processes(2).build(),
                SandboxResourceLimits.builder().processes(2).memoryBytes(5).build());
    }

    @Test
    void everyBuiltInBackendRejectsCpuDiskAndVmBeforePreparation() throws Exception {
        SandboxClient client = SandboxClient.create();
        for (SandboxResourceLimits limits :
                Arrays.asList(
                        SandboxResourceLimits.builder().cpuTime(Duration.ofMillis(1)).build(),
                        SandboxResourceLimits.builder().diskBytes(1).build())) {
            SandboxPolicyReadiness check = client.readiness(request(limits));
            assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, check.error().get().code());
            assertEquals(SandboxPhase.ADMISSION, check.error().get().phase());
            assertEquals(SandboxCleanupState.NOT_REQUIRED, check.cleanup().temporaryFiles());
        }
        SandboxPolicyReadiness vm =
                client.readiness(
                        SandboxRequest.builder(workspace, "synthetic")
                                .requiredIsolation(SandboxIsolationLevel.VIRTUAL_MACHINE)
                                .build());
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, vm.error().get().code());
        assertEquals(SandboxCleanupState.NOT_REQUIRED, vm.cleanup().processes());
    }

    @Test
    void hostExtensionRequiresBothHardCapabilityEvidenceAndExplicitPolicySupport()
            throws Exception {
        AtomicInteger probes = new AtomicInteger();
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "synthetic-host-contract";
                    }

                    public boolean supportsResourceLimits(SandboxResourceLimits limits) {
                        return limits.cpuTimeMillis() == 0 && limits.diskBytes() == 0;
                    }

                    public void validate(SandboxRequest request) {
                        probes.incrementAndGet();
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("contract only");
                    }
                };
        for (SandboxGuarantee guarantee :
                new SandboxGuarantee[] {
                    SandboxGuarantee.UNKNOWN, SandboxGuarantee.BEST_EFFORT, SandboxGuarantee.KERNEL
                }) {
            Map<SandboxCapability, SandboxCapabilitySupport> dimensions =
                    new EnumMap<>(SandboxCapability.class);
            dimensions.put(
                    SandboxCapability.MEMORY_LIMIT,
                    new SandboxCapabilitySupport(guarantee, "synthetic-contract-only"));
            SandboxCapabilities caps =
                    new SandboxCapabilities(
                            SandboxPlatform.current(),
                            runner.backendName(),
                            EnumSet.allOf(ReadPolicy.class),
                            EnumSet.allOf(NetworkPolicy.class),
                            EnumSet.allOf(DeletionPolicy.class),
                            false,
                            SandboxEnforcement.PARTIAL,
                            dimensions);
            SandboxClient client =
                    SandboxClient.builder()
                            .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, ""))
                            .build();
            SandboxPolicyReadiness check =
                    client.readiness(
                            request(SandboxResourceLimits.builder().memoryBytes(1024).build()));
            assertEquals(guarantee == SandboxGuarantee.KERNEL, check.ready());
        }
        assertEquals(1, probes.get());
    }
}
