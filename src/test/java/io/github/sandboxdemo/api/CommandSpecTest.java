package io.github.sandboxdemo.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandSpecTest {

    @Test
    void rejectsNulAndOversizedCommands() {
        assertThrows(
                IllegalArgumentException.class, () -> CommandSpec.of("/bin/sh", "bad\0argument"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommandSpec("/bin/sh", List.of("x".repeat(30_000))));
    }
}
