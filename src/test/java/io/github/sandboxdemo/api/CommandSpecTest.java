package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CommandSpecTest {

    @Test
    void rejectsNulAndOversizedCommands() {
        assertThrows(
                IllegalArgumentException.class, () -> CommandSpec.of("/bin/sh", "bad\0argument"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CommandSpec(
                                "/bin/sh",
                                io.github.sandboxdemo.core.Java8.listOf(
                                        io.github.sandboxdemo.core.Java8.repeat("x", 30_000))));
    }
}
