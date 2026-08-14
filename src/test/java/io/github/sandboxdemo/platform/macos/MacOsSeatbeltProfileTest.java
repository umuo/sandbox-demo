package io.github.sandboxdemo.platform.macos;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.api.SandboxPolicy;
import io.github.sandboxdemo.core.PathPolicyValidator;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacOsSeatbeltProfileTest {

    @TempDir Path workspace;

    @Test
    void declaredReadPolicyDoesNotEnableBlanketHostReads() throws Exception {
        ValidatedPolicy policy =
                PathPolicyValidator.validate(
                        SandboxPolicy.builder(workspace)
                                .readPolicy(ReadPolicy.DECLARED_ONLY)
                                .build());
        try {
            MacOsSeatbeltProfile.GeneratedProfile generated = MacOsSeatbeltProfile.generate(policy);
            assertFalse(generated.profile().contains("\n(allow file-read*)\n"));
            assertTrue(generated.profile().contains("READABLE_0"));
            assertFalse(generated.profile().contains("(allow mach-lookup)"));
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    @Test
    void hostReadPolicyIsExplicit() throws Exception {
        ValidatedPolicy policy =
                PathPolicyValidator.validate(
                        SandboxPolicy.builder(workspace).readPolicy(ReadPolicy.HOST).build());
        try {
            assertTrue(
                    MacOsSeatbeltProfile.generate(policy)
                            .profile()
                            .contains("\n(allow file-read*)\n"));
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }

    @Test
    void readOnlyWorkingDirectoryGrantsWritesOnlyToPrivateTemp() throws Exception {
        ValidatedPolicy policy =
                PathPolicyValidator.validate(
                        SandboxPolicy.builder(workspace)
                                .readOnlyWorkingDirectory()
                                .readPolicy(ReadPolicy.DECLARED_ONLY)
                                .build());
        try {
            MacOsSeatbeltProfile.GeneratedProfile generated = MacOsSeatbeltProfile.generate(policy);
            java.util.List<String> writableDefinitions =
                    generated.definitions().stream()
                            .filter(definition -> definition.startsWith("-DWRITABLE_"))
                            .collect(java.util.stream.Collectors.toList());

            assertTrue(writableDefinitions.size() == 1);
            assertTrue(
                    writableDefinitions.get(0).endsWith(policy.privateTempDirectory().toString()));
            assertFalse(writableDefinitions.get(0).endsWith(workspace.toRealPath().toString()));
        } finally {
            PathPolicyValidator.cleanup(policy);
        }
    }
}
