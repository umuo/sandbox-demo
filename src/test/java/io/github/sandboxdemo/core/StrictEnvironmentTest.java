package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StrictEnvironmentTest {
    @TempDir Path root;

    private SandboxRequest.Builder request(Path tools) throws Exception {
        Path work = Files.createDirectories(root.resolve("work"));
        return SandboxRequest.builder(work, "synthetic-tool")
                .allowPathSearch(true)
                .executionEnvironment(SandboxEnvironment.strict(tools));
    }

    @Test
    void strictEnvironmentOnlyContainsControlledAndExplicitlyAllowedValues() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        SandboxRequest request =
                request(tools)
                        .executionEnvironment(
                                SandboxEnvironment.strict(tools).allowVariable("APP_VALUE"))
                        .environment("APP_VALUE", "synthetic")
                        .build();
        ValidatedPolicy policy = PathPolicyValidator.validate(request);
        try {
            Map<String, String> env = EnvironmentPolicy.build(request, policy);
            assertEquals(tools.toRealPath().toString(), env.get("PATH"));
            assertEquals("synthetic", env.get("APP_VALUE"));
            assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"));
            assertFalse(env.containsKey("LANG"));
            assertTrue(Paths.get(env.get("HOME")).startsWith(policy.privateTempDirectory()));
            assertNotEquals(policy.workingDirectory().toString(), env.get("HOME"));
            assertTrue(policy.readableRoots().contains(tools.toRealPath()));
            assertFalse(policy.writableRoots().contains(tools.toRealPath()));
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    @Test
    void loaderVariablesAndCallerPathCannotBeAllowedOrPassed() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        for (String name :
                Arrays.asList(
                        "PATH",
                        "Path",
                        "LD_PRELOAD",
                        "DYLD_INSERT_LIBRARIES",
                        "JAVA_TOOL_OPTIONS",
                        "_JAVA_OPTIONS",
                        "BASH_ENV",
                        "NODE_OPTIONS",
                        "PYTHONPATH")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SandboxEnvironment.strict(tools).allowVariable(name));
            SandboxRequest request = request(tools).environment(name, "synthetic-secret").build();
            SandboxPolicyReadiness result = SandboxClient.create().readiness(request);
            assertFalse(result.ready());
            assertEquals(SandboxErrorCode.POLICY_INVALID, result.error().get().code());
            assertEquals(SandboxPhase.ADMISSION, result.error().get().phase());
            assertEquals(SandboxCleanupState.NOT_REQUIRED, result.cleanup().temporaryFiles());
            assertFalse(result.toString().contains("synthetic-secret"));
        }
        assertThrows(
                SandboxException.class,
                () ->
                        EnvironmentPolicy.validate(
                                request(tools).environment("UNAPPROVED", "synthetic").build()));
    }

    @Test
    void executableSearchNeverFallsBackToWorkspaceOrHostPath() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        SandboxRequest request = request(tools).build();
        Path fake = Files.write(root.resolve("work/synthetic-tool"), new byte[] {1});
        fake.toFile().setExecutable(true);
        ValidatedPolicy policy = PathPolicyValidator.validate(request);
        try {
            Map<String, String> env = EnvironmentPolicy.build(request, policy);
            assertThrows(
                    SandboxException.class,
                    () -> EnvironmentPolicy.resolveExecutable(request, policy, env));
            SandboxRequest direct =
                    SandboxRequest.builder(root.resolve("work"), fake.toString())
                            .executionEnvironment(SandboxEnvironment.strict(tools))
                            .build();
            assertEquals(
                    SandboxErrorCode.POLICY_INVALID,
                    assertThrows(
                                    SandboxException.class,
                                    () -> EnvironmentPolicy.resolveExecutable(direct, policy, env))
                            .code());
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    @Test
    void writableToolDirectoriesAreRejected() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        SandboxRequest request = request(tools).writableRoot(tools).build();
        assertEquals(
                SandboxErrorCode.POLICY_INVALID,
                assertThrows(SandboxException.class, () -> PathPolicyValidator.validate(request))
                        .code());
    }

    @Test
    void concurrentExecutionsHaveSeparateHomeAndTemporaryResources() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        SandboxRequest request = request(tools).privateTempParent(root).build();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ValidatedPolicy first = null, second = null;
        try {
            Future<ValidatedPolicy> one =
                    workers.submit(() -> PathPolicyValidator.validate(request));
            Future<ValidatedPolicy> two =
                    workers.submit(() -> PathPolicyValidator.validate(request));
            first = one.get(10, TimeUnit.SECONDS);
            second = two.get(10, TimeUnit.SECONDS);
            assertNotEquals(first.privateTempDirectory(), second.privateTempDirectory());
            assertNotEquals(
                    EnvironmentPolicy.build(request, first).get("HOME"),
                    EnvironmentPolicy.build(request, second).get("HOME"));
            assertEquals(root.toRealPath(), first.privateTempDirectory().getParent());
        } finally {
            if (first != null) {
                PathPolicyValidator.cleanup(first);
            }
            if (second != null) {
                PathPolicyValidator.cleanup(second);
            }
            workers.shutdownNow();
        }
    }

    @Test
    void legacyCustomBackendMustOptIntoStrictEnvironment() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        SandboxRunner runner =
                new SandboxRunner() {
                    public String backendName() {
                        return "legacy-custom";
                    }

                    public SandboxResult execute(SandboxRequest request) {
                        throw new AssertionError("must not execute");
                    }
                };
        SandboxCapabilities caps =
                new SandboxCapabilities(
                        SandboxPlatform.current(),
                        runner.backendName(),
                        Collections.singleton(ReadPolicy.DECLARED_ONLY),
                        Collections.singleton(NetworkPolicy.DENY),
                        false);
        SandboxClient client =
                SandboxClient.builder()
                        .backend(runner, caps, () -> new SandboxRuntimeStatus(caps, true, ""))
                        .build();
        SandboxPolicyReadiness readiness = client.readiness(request(tools).build());
        assertEquals(SandboxErrorCode.POLICY_UNSUPPORTED, readiness.error().get().code());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs({
        org.junit.jupiter.api.condition.OS.MAC,
        org.junit.jupiter.api.condition.OS.LINUX
    })
    void resolvedSymlinkCannotEscapeToolchainIntoWritableWorkspace() throws Exception {
        Path tools = Files.createDirectory(root.resolve("tools"));
        SandboxRequest request = request(tools).build();
        Path executable = Files.write(root.resolve("work/synthetic-tool"), new byte[] {1});
        executable.toFile().setExecutable(true);
        Files.createSymbolicLink(tools.resolve("synthetic-tool"), executable);
        ValidatedPolicy policy = PathPolicyValidator.validate(request);
        try {
            assertEquals(
                    SandboxErrorCode.POLICY_INVALID,
                    assertThrows(
                                    SandboxException.class,
                                    () ->
                                            EnvironmentPolicy.resolveExecutable(
                                                    request,
                                                    policy,
                                                    EnvironmentPolicy.build(request, policy)))
                            .code());
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }
}
