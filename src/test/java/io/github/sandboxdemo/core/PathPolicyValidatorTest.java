package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.sandboxdemo.api.SandboxPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPolicyValidatorTest {

    @TempDir Path root;

    @Test
    void supportsAReadOnlyCwdAndAddsAPrivateWritableTemp() throws Exception {
        Path project = Files.createDirectory(root.resolve("project"));
        SandboxPolicy policy = SandboxPolicy.builder(project).readOnlyWorkingDirectory().build();

        ValidatedPolicy validated = PathPolicyValidator.validate(policy);
        try {
            assertFalse(validated.writableRoots().contains(project.toRealPath()));
            assertTrue(validated.writableRoots().size() == 1);
            assertTrue(validated.writableRoots().contains(validated.privateTempDirectory()));
            assertTrue(validated.readableRoots().contains(validated.privateTempDirectory()));
            assertTrue(Files.isDirectory(validated.privateTempDirectory()));
        } finally {
            PathPolicyValidator.cleanup(validated);
        }
        assertFalse(Files.exists(validated.privateTempDirectory()));
    }
}
