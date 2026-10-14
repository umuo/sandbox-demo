package io.github.sandboxdemo.core;

import static org.junit.jupiter.api.Assertions.*;

import io.github.sandboxdemo.api.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OutputLimitTest {
    private LimitedOutput capture(
            int limit, OutputBudget budget, OutputCallbackMode mode, AtomicInteger observed) {
        return new LimitedOutput(
                limit,
                chunk -> observed.addAndGet(chunk.length),
                null,
                StandardCharsets.UTF_8,
                false,
                budget,
                mode);
    }

    @Test
    void legacyRawCallbacksObserveBytesBeyondTheRetainedPrefix() {
        AtomicInteger observed = new AtomicInteger();
        LimitedOutput output =
                capture(
                        3,
                        new OutputBudget(0, OutputLimitAction.TRUNCATE, null),
                        OutputCallbackMode.RAW,
                        observed);
        output.accept(new byte[9], 9);
        assertEquals(9, observed.get());
        assertEquals(3, output.bytes().length);
        assertTrue(output.truncated());
    }

    @Test
    void retainedCallbacksAndBothStreamsShareOneBudget() {
        OutputBudget budget = new OutputBudget(5, OutputLimitAction.TRUNCATE, null);
        AtomicInteger observed = new AtomicInteger();
        LimitedOutput stdout = capture(4, budget, OutputCallbackMode.RETAINED, observed);
        LimitedOutput stderr = capture(4, budget, OutputCallbackMode.RETAINED, observed);
        stdout.accept(new byte[3], 3);
        stderr.accept(new byte[3], 3);
        stdout.accept(new byte[4], 4);
        assertEquals(5, observed.get());
        assertEquals(5, stdout.bytes().length + stderr.bytes().length);
        assertTrue(stdout.truncated());
        assertTrue(stderr.truncated());
        assertFalse(budget.shouldStopReading());
    }

    @Test
    void simultaneousStreamsCannotOverdrawCombinedBudget() throws Exception {
        OutputBudget budget = new OutputBudget(513, OutputLimitAction.TRUNCATE, null);
        AtomicInteger observed = new AtomicInteger();
        LimitedOutput stdout = capture(1024, budget, OutputCallbackMode.RETAINED, observed);
        LimitedOutput stderr = capture(1024, budget, OutputCallbackMode.RETAINED, observed);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> a =
                    workers.submit(
                            () -> {
                                try {
                                    start.await();
                                    stdout.accept(new byte[1024], 1024);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            Future<?> b =
                    workers.submit(
                            () -> {
                                try {
                                    start.await();
                                    stderr.accept(new byte[1024], 1024);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            start.countDown();
            a.get(1, TimeUnit.SECONDS);
            b.get(1, TimeUnit.SECONDS);
            assertEquals(513, observed.get());
            assertEquals(513, stdout.bytes().length + stderr.bytes().length);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void terminationIsRecordedBeforeCallbackAndExactLimitIsNotAnOverflow() {
        ExecutionContext control =
                new ExecutionContext(Duration.ofSeconds(10), Duration.ofMillis(100));
        OutputBudget budget = new OutputBudget(0, OutputLimitAction.TERMINATE, control);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<SandboxTerminationReason> observedReason =
                new java.util.concurrent.atomic.AtomicReference<>();
        LimitedOutput output =
                new LimitedOutput(
                        3,
                        bytes -> {
                            if (calls.incrementAndGet() == 2) {
                                observedReason.set(control.stopReason());
                            }
                        },
                        null,
                        StandardCharsets.UTF_8,
                        false,
                        budget,
                        OutputCallbackMode.RAW);
        output.accept(new byte[3], 3);
        assertNull(control.stopReason());
        output.accept(new byte[1], 1);
        assertEquals(SandboxTerminationReason.OUTPUT_LIMIT, control.stopReason());
        assertEquals(SandboxTerminationReason.OUTPUT_LIMIT, observedReason.get());
        assertEquals(
                SandboxErrorCode.OUTPUT_LIMIT,
                assertThrows(SandboxException.class, control::checkActive).code());
    }

    @Test
    void completeSuccessRejectsTruncationAndOutputLimitWithoutChangingLegacySuccess() {
        SandboxResult truncated =
                new SandboxResult(0, false, new byte[1], new byte[0], true, false, Duration.ZERO);
        assertTrue(truncated.successful());
        assertFalse(truncated.completeSuccessful());
        SandboxResult stopped =
                new SandboxResult(
                        0, false, new byte[0], new byte[0], false, false, Duration.ZERO, true);
        assertTrue(stopped.successful());
        assertFalse(stopped.completeSuccessful());
        assertTrue(
                new SandboxResult(0, false, new byte[0], new byte[0], false, false, Duration.ZERO)
                        .completeSuccessful());
    }

    @Test
    void autoDecoderDoesNotBufferAnUnboundedValidUtf8PrefixEndingInAPartialCharacter() {
        AtomicInteger delivered = new AtomicInteger();
        OutputCallbacks callbacks =
                new OutputCallbacks(
                        null,
                        text -> delivered.addAndGet(text.length()),
                        StandardCharsets.UTF_8,
                        true);
        byte[] prefix = new byte[8192];
        byte[] character = "€".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < prefix.length; i++) {
            prefix[i] = character[i % 3];
        }
        callbacks.accept(prefix);
        assertTrue(delivered.get() > 2000, "valid prefix must be decoded before EOF");
        callbacks.accept(new byte[] {character[2]});
        callbacks.complete();
        assertEquals(2731, delivered.get());
    }
}
