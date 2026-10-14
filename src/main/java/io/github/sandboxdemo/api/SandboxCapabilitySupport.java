package io.github.sandboxdemo.api;

import java.util.Objects;

/** 某项能力的强制机制与保证级别，不含执行策略或敏感路径。 */
public final class SandboxCapabilitySupport {
    private final SandboxGuarantee guarantee;
    private final String mechanism;

    public SandboxCapabilitySupport(SandboxGuarantee guarantee, String mechanism) {
        this.guarantee = Objects.requireNonNull(guarantee, "guarantee");
        this.mechanism = Objects.requireNonNull(mechanism, "mechanism");
    }

    public SandboxGuarantee guarantee() {
        return guarantee;
    }

    public String mechanism() {
        return mechanism;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SandboxCapabilitySupport)) {
            return false;
        }
        SandboxCapabilitySupport that = (SandboxCapabilitySupport) other;
        return guarantee == that.guarantee && mechanism.equals(that.mechanism);
    }

    @Override
    public int hashCode() {
        return Objects.hash(guarantee, mechanism);
    }

    @Override
    public String toString() {
        return guarantee + "[" + mechanism + "]";
    }
}
