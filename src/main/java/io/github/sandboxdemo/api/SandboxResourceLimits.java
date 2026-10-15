package io.github.sandboxdemo.api;

import java.time.Duration;
import java.util.Objects;

/** 逐请求硬限制。0 表示未请求该维度，不表示后端无限制；不支持的非零维度必须拒绝。 内存为进程树总提交内存，CPU 为进程树累计 CPU 时间，磁盘涵盖全部可写根及私有 temp。 */
public final class SandboxResourceLimits {
    private final long memoryBytes, cpuTimeMillis, diskBytes;
    private final int processes;

    private SandboxResourceLimits(Builder builder) {
        memoryBytes = builder.memoryBytes;
        cpuTimeMillis = builder.cpuTimeMillis;
        diskBytes = builder.diskBytes;
        processes = builder.processes;
    }

    public static SandboxResourceLimits none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public long memoryBytes() {
        return memoryBytes;
    }

    public int processes() {
        return processes;
    }

    public long cpuTimeMillis() {
        return cpuTimeMillis;
    }

    public long diskBytes() {
        return diskBytes;
    }

    public boolean isEmpty() {
        return memoryBytes == 0 && processes == 0 && cpuTimeMillis == 0 && diskBytes == 0;
    }

    public static final class Builder {
        private long memoryBytes, cpuTimeMillis, diskBytes;
        private int processes;

        private Builder() {}

        public Builder memoryBytes(long bytes) {
            memoryBytes = positive(bytes);
            return this;
        }

        public Builder processes(int maximum) {
            processes = (int) positive(maximum);
            return this;
        }

        public Builder diskBytes(long bytes) {
            diskBytes = positive(bytes);
            return this;
        }

        public Builder cpuTime(Duration duration) {
            Objects.requireNonNull(duration, "duration");
            if (duration.isNegative()
                    || duration.isZero()
                    || duration.compareTo(Duration.ofHours(24)) > 0
                    || duration.getNano() % 1_000_000 != 0) {
                throw new IllegalArgumentException(
                        "CPU budget must be whole milliseconds, positive, and at most 24 hours");
            }
            cpuTimeMillis = positive(duration.toMillis());
            return this;
        }

        private static long positive(long value) {
            if (value < 1) {
                throw new IllegalArgumentException("resource limit must be positive");
            }
            return value;
        }

        public SandboxResourceLimits build() {
            return new SandboxResourceLimits(this);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SandboxResourceLimits)) {
            return false;
        }
        SandboxResourceLimits that = (SandboxResourceLimits) other;
        return memoryBytes == that.memoryBytes
                && processes == that.processes
                && cpuTimeMillis == that.cpuTimeMillis
                && diskBytes == that.diskBytes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(memoryBytes, processes, cpuTimeMillis, diskBytes);
    }

    @Override
    public String toString() {
        return "SandboxResourceLimits[memoryBytes="
                + memoryBytes
                + ", processes="
                + processes
                + ", cpuTimeMillis="
                + cpuTimeMillis
                + ", diskBytes="
                + diskBytes
                + "]";
    }
}
