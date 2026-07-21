package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPolicyTest {

    @TempDir Path temp;

    @Test
    void workingDirectoryIsWritableByDefault() {
        SandboxPolicy policy = SandboxPolicy.builder(temp).build();
        assertEquals(List.of(temp.toAbsolutePath().normalize()), policy.writableRoots());
        assertEquals(List.of(temp.toAbsolutePath().normalize()), policy.readableRoots());
        assertEquals(ReadPolicy.DECLARED_ONLY, policy.readPolicy());
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

        assertEquals(List.of(tests.toAbsolutePath().normalize()), policy.writableRoots());
        assertEquals(true, policy.readableRoots().contains(temp.toAbsolutePath().normalize()));
    }
}
