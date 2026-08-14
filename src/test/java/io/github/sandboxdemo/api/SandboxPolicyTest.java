package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPolicyTest {

    @TempDir Path temp;

    @Test
    void workingDirectoryIsWritableByDefault() {
        SandboxPolicy policy = SandboxPolicy.builder(temp).build();
        assertEquals(
                io.github.sandboxdemo.core.Java8.listOf(temp.toAbsolutePath().normalize()),
                policy.writableRoots());
        assertEquals(
                io.github.sandboxdemo.core.Java8.listOf(temp.toAbsolutePath().normalize()),
                policy.readableRoots());
        assertEquals(
                SandboxPlatform.current() == SandboxPlatform.WINDOWS
                        ? ReadPolicy.HOST
                        : ReadPolicy.DECLARED_ONLY,
                policy.readPolicy());
        assertEquals(
                SandboxPlatform.current() == SandboxPlatform.WINDOWS
                        ? NetworkPolicy.ALLOW
                        : NetworkPolicy.DENY,
                policy.networkPolicy());
        assertEquals(DeletionPolicy.ALLOW, policy.deletionPolicy());
    }

    @Test
    void writableRootIsReadableAndOutputHasProtocolBound() {
        Path generated = temp.resolve("generated");
        SandboxPolicy policy = SandboxPolicy.builder(temp).writableRoot(generated).build();
        assertEquals(true, policy.readableRoots().contains(generated.toAbsolutePath().normalize()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxPolicy.builder(temp).maxOutputBytes(64 * 1024 * 1024 + 1).build());
    }

    @Test
    void protectedPathMustBeNestedInWritableRoot() {
        Path outside = temp.resolveSibling("outside");
        assertThrows(
                IllegalArgumentException.class,
                () -> SandboxPolicy.builder(temp).protect(outside).build());
    }

    @Test
    void workingDirectoryCanBeReadOnlyWithANarrowWritableChild() {
        Path tests = temp.resolve("src").resolve("test").resolve("java");

        SandboxPolicy policy =
                SandboxPolicy.builder(temp).readOnlyWorkingDirectory().writableRoot(tests).build();

        assertEquals(
                io.github.sandboxdemo.core.Java8.listOf(tests.toAbsolutePath().normalize()),
                policy.writableRoots());
        assertEquals(true, policy.readableRoots().contains(temp.toAbsolutePath().normalize()));
    }

    @Test
    void workingDirectoryCanBeFullyReadOnly() {
        SandboxPolicy policy = SandboxPolicy.builder(temp).readOnlyWorkingDirectory().build();

        assertEquals(io.github.sandboxdemo.core.Java8.listOf(), policy.writableRoots());
        assertEquals(
                io.github.sandboxdemo.core.Java8.listOf(temp.toAbsolutePath().normalize()),
                policy.readableRoots());
    }

    @Test
    void deletionCanBeDeniedIndependentlyFromWriteRoots() {
        SandboxPolicy policy = SandboxPolicy.builder(temp).deletion(DeletionPolicy.DENY).build();

        assertEquals(DeletionPolicy.DENY, policy.deletionPolicy());
        assertEquals(
                io.github.sandboxdemo.core.Java8.listOf(temp.toAbsolutePath().normalize()),
                policy.writableRoots());
    }
}
