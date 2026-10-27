package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.io.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class LimitedOutputSemanticsTest {
    @Test
    void callbackMatrixPreservesPrefixesAndSharedQuotaWithBothOverflowActions() {
        for (int consumers = 0; consumers < 4; consumers++) {
            for (OutputCallbackMode mode : OutputCallbackMode.values()) {
                for (OutputLimitAction action : OutputLimitAction.values()) {
                    ExecutionContext context =
                            new ExecutionContext(Duration.ofSeconds(10), Duration.ofSeconds(1));
                    OutputBudget budget = new OutputBudget(5, action, context);
                    StringBuilder[] observedBytes = {new StringBuilder(), new StringBuilder()};
                    StringBuilder[] observedText = {new StringBuilder(), new StringBuilder()};
                    LimitedOutput[] streams = new LimitedOutput[2];
                    for (int i = 0; i < 2; i++) {
                        final int index = i;
                        Consumer<byte[]> bytes =
                                (consumers & 1) == 0
                                        ? null
                                        : chunk ->
                                                observedBytes[index].append(
                                                        new String(
                                                                chunk, StandardCharsets.US_ASCII));
                        Consumer<String> text =
                                (consumers & 2) == 0 ? null : observedText[index]::append;
                        streams[i] =
                                new LimitedOutput(
                                        4,
                                        bytes,
                                        text,
                                        StandardCharsets.UTF_8,
                                        false,
                                        budget,
                                        mode);
                    }
                    streams[0].accept("abcdSTALE".getBytes(StandardCharsets.US_ASCII), 4);
                    assertFalse(streams[0].truncated());
                    assertFalse(budget.shouldStopReading());
                    streams[1].accept("wxyzSTALE".getBytes(StandardCharsets.US_ASCII), 4);
                    streams[0].accept(new byte[] {'q', 'S'}, 1);
                    streams[0].complete();
                    streams[1].complete();
                    assertArrayEquals(
                            "abcd".getBytes(StandardCharsets.US_ASCII), streams[0].bytes());
                    assertArrayEquals(new byte[] {'w'}, streams[1].bytes());
                    assertTrue(streams[0].truncated());
                    assertTrue(streams[1].truncated());
                    assertEquals(action == OutputLimitAction.TERMINATE, budget.shouldStopReading());
                    assertEquals(
                            action == OutputLimitAction.TERMINATE
                                    ? SandboxTerminationReason.OUTPUT_LIMIT
                                    : null,
                            context.stopReason());
                    String[] expected =
                            mode == OutputCallbackMode.RAW
                                    ? new String[] {"abcdq", "wxyz"}
                                    : new String[] {"abcd", "w"};
                    for (int i = 0; i < 2; i++) {
                        assertEquals(
                                (consumers & 1) == 0 ? "" : expected[i],
                                observedBytes[i].toString());
                        assertEquals(
                                (consumers & 2) == 0 ? "" : expected[i],
                                observedText[i].toString());
                    }
                }
            }
        }
    }

    @Test
    void mutatingByteCallbackCannotChangeBorrowedInputTextOrRetainedResult() {
        for (OutputCallbackMode mode : OutputCallbackMode.values()) {
            StringBuilder text = new StringBuilder();
            List<byte[]> owned = new ArrayList<>();
            LimitedOutput capture =
                    new LimitedOutput(
                            8,
                            chunk -> {
                                owned.add(chunk);
                                Arrays.fill(chunk, (byte) 'x');
                            },
                            text::append,
                            StandardCharsets.UTF_8,
                            false,
                            new OutputBudget(0, OutputLimitAction.TRUNCATE, null),
                            mode);
            byte[] reused = "helloSTALE".getBytes(StandardCharsets.UTF_8);
            capture.accept(reused, 5);
            assertEquals("helloSTALE", new String(reused, StandardCharsets.UTF_8));
            Arrays.fill(reused, (byte) '!');
            capture.accept(reused, 3);
            capture.complete();
            assertEquals("hello!!!", text.toString());
            assertNotSame(owned.get(0), owned.get(1));
            byte[] retained = capture.bytes();
            SandboxResult result =
                    new SandboxResult(0, false, retained, new byte[0], false, false, Duration.ZERO);
            Arrays.fill(retained, (byte) '?');
            assertEquals("hello!!!", new String(capture.bytes(), StandardCharsets.UTF_8));
            assertEquals("hello!!!", result.stdoutUtf8());
            Arrays.fill(result.stdout(), (byte) '?');
            assertEquals("hello!!!", result.stdoutUtf8());
        }
    }

    @Test
    void streamingDecoderPreservesSplitUtf8AndUtf16WithAndWithoutBom() {
        String expected = "hello 中文 € end";
        for (Charset charset :
                Arrays.asList(
                        StandardCharsets.UTF_8,
                        StandardCharsets.UTF_16LE,
                        StandardCharsets.UTF_16BE)) {
            for (boolean auto : new boolean[] {false, true}) {
                for (boolean bom : new boolean[] {false, true}) {
                    if (bom && !auto) continue;
                    byte[] content = expected.getBytes(charset);
                    byte[] prefix =
                            !bom
                                    ? new byte[0]
                                    : charset.equals(StandardCharsets.UTF_8)
                                            ? new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}
                                            : charset.equals(StandardCharsets.UTF_16LE)
                                                    ? new byte[] {(byte) 0xff, (byte) 0xfe}
                                                    : new byte[] {(byte) 0xfe, (byte) 0xff};
                    byte[] encoded = new byte[prefix.length + content.length];
                    System.arraycopy(prefix, 0, encoded, 0, prefix.length);
                    System.arraycopy(content, 0, encoded, prefix.length, content.length);
                    for (OutputCallbackMode mode : OutputCallbackMode.values()) {
                        StringBuilder decoded = new StringBuilder();
                        LimitedOutput capture =
                                new LimitedOutput(
                                        encoded.length,
                                        null,
                                        decoded::append,
                                        auto ? StandardCharsets.UTF_8 : charset,
                                        auto,
                                        new OutputBudget(0, OutputLimitAction.TRUNCATE, null),
                                        mode);
                        byte[] borrowed = new byte[7];
                        for (byte value : encoded) {
                            Arrays.fill(borrowed, (byte) 0x7f);
                            borrowed[0] = value;
                            capture.accept(borrowed, 1);
                        }
                        capture.complete();
                        capture.complete();
                        assertEquals(
                                expected,
                                decoded.toString(),
                                charset + " auto=" + auto + " bom=" + bom + " mode=" + mode);
                        assertArrayEquals(encoded, capture.bytes());
                        assertFalse(capture.truncated());
                    }
                }
            }
        }
    }

    @Test
    void noCallbacksStillDrainsInputAndDetectsOneByteBeyondExactLimit() throws Exception {
        LimitedOutput exact = new LimitedOutput(3);
        exact.readFrom(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        assertFalse(exact.truncated());
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[32768]);
        LimitedOutput overflow = new LimitedOutput(3);
        overflow.readFrom(input);
        assertEquals(0, input.available());
        assertEquals(3, overflow.bytes().length);
        assertTrue(overflow.truncated());
        LimitedOutput empty = new LimitedOutput(3);
        empty.accept(new byte[] {42}, 0);
        assertEquals(0, empty.bytes().length);
        assertFalse(empty.truncated());
        assertThrows(IllegalArgumentException.class, () -> empty.accept(new byte[1], -1));
        assertThrows(IllegalArgumentException.class, () -> empty.accept(new byte[1], 2));
    }
}
