package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.OutputCallbackMode;
import io.github.sandboxdemo.api.OutputLimitAction;
import java.lang.management.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/** Standalone bounded capture benchmark, deliberately excluded from unit-test timing gates. */
public final class OutputAllocationBenchmark {
    private static volatile Object blackhole;
    private static volatile long checksum;

    private static void capture(String mode, int total) {
        byte[] buffer = new byte[8192];
        Arrays.fill(buffer, (byte) 'a');
        Consumer<byte[]> bytes =
                mode.equals("byte") || mode.equals("both") ? chunk -> blackhole = chunk : null;
        Consumer<String> text =
                mode.equals("text") || mode.equals("both") ? chunk -> blackhole = chunk : null;
        LimitedOutput output =
                new LimitedOutput(
                        8 * 1024 * 1024,
                        bytes,
                        text,
                        StandardCharsets.UTF_8,
                        false,
                        new OutputBudget(0, OutputLimitAction.TRUNCATE, null),
                        OutputCallbackMode.RAW);
        for (int offset = 0; offset < total; offset += buffer.length)
            output.accept(buffer, Math.min(buffer.length, total - offset));
        output.complete();
        byte[] retained = output.bytes();
        if (retained.length != Math.min(total, 8 * 1024 * 1024)
                || output.truncated() != (total > retained.length))
            throw new AssertionError("capture changed");
        checksum += retained.length + retained[0] + retained[retained.length - 1];
        blackhole = retained;
    }

    private static long gc(boolean time) {
        long value = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long sample = time ? bean.getCollectionTime() : bean.getCollectionCount();
            if (sample >= 0) value += sample;
        }
        return value;
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int total = Integer.parseInt(args[1]) * 1024 * 1024;
        if (!Arrays.asList("none", "byte", "text", "both").contains(mode)
                || (total != 8 * 1024 * 1024 && total != 32 * 1024 * 1024))
            throw new IllegalArgumentException();
        Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
        Object bean = ManagementFactory.getThreadMXBean();
        if (!Boolean.TRUE.equals(type.getMethod("isThreadAllocatedMemorySupported").invoke(bean)))
            throw new IllegalStateException("allocation counter unavailable");
        type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
        Method allocated = type.getMethod("getThreadAllocatedBytes", long.class);
        long thread = Thread.currentThread().getId();
        for (int i = 0; i < 3; i++) capture(mode, total);
        blackhole = null;
        System.gc();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        long gcCount = gc(false), gcMillis = gc(true);
        long before = (Long) allocated.invoke(bean, thread), started = System.nanoTime();
        for (int i = 0; i < 5; i++) capture(mode, total);
        long elapsed = System.nanoTime() - started,
                allocation = (Long) allocated.invoke(bean, thread) - before;
        long peakSum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            if (pool.getType() == MemoryType.HEAP) peakSum += pool.getPeakUsage().getUsed();
        System.out.println(
                "{\"allocated_bytes\":"
                        + allocation
                        + ",\"elapsed_ns\":"
                        + elapsed
                        + ",\"gc_count\":"
                        + (gc(false) - gcCount)
                        + ",\"gc_ms\":"
                        + (gc(true) - gcMillis)
                        + ",\"heap_pool_peak_sum_bytes\":"
                        + peakSum
                        + ",\"iterations\":5,\"checksum\":"
                        + checksum
                        + "}");
    }
}
