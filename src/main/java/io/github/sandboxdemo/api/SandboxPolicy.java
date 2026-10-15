package io.github.sandboxdemo.api;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, cross-platform sandbox policy.
 *
 * <p>The default read policy exposes only platform runtime paths plus declared readable/writable
 * roots. Callers must opt into broad host reads explicitly.
 */
public final class SandboxPolicy {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 64 * 1024 * 1024;
    private static final int MAX_PATHS_PER_CATEGORY = 256;
    private static final Duration MAX_TIMEOUT = Duration.ofHours(24);

    private final SandboxResourceLimits resourceLimits;
    private final SandboxIsolationLevel requiredIsolation;
    private final Path workingDirectory;
    private final List<Path> readableRoots;
    private final List<Path> writableRoots;
    private final List<Path> protectedPaths;
    private final NetworkPolicy networkPolicy;
    private final ReadPolicy readPolicy;
    private final DeletionPolicy deletionPolicy;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final boolean allowPathSearch;
    private final OutputLimitAction onOutputLimit;
    private final int maxCombinedOutputBytes;
    private final OutputCallbackMode outputCallbackMode;

    private SandboxPolicy(Builder builder) {
        this.resourceLimits = Objects.requireNonNull(builder.resourceLimits, "resourceLimits");
        this.requiredIsolation =
                Objects.requireNonNull(builder.requiredIsolation, "requiredIsolation");
        this.workingDirectory = normalize(builder.workingDirectory);
        this.readableRoots = immutableNormalized(builder.readableRoots);
        this.writableRoots = immutableNormalized(builder.writableRoots);
        this.protectedPaths = immutableNormalized(builder.protectedPaths);
        this.networkPolicy = Objects.requireNonNull(builder.networkPolicy, "networkPolicy");
        this.readPolicy = Objects.requireNonNull(builder.readPolicy, "readPolicy");
        this.deletionPolicy = Objects.requireNonNull(builder.deletionPolicy, "deletionPolicy");
        this.timeout = Objects.requireNonNull(builder.timeout, "timeout");
        this.maxOutputBytes = builder.maxOutputBytes;
        this.allowPathSearch = builder.allowPathSearch;
        this.onOutputLimit = builder.onOutputLimit;
        this.maxCombinedOutputBytes = builder.maxCombinedOutputBytes;
        this.outputCallbackMode = builder.outputCallbackMode;
        if (maxCombinedOutputBytes < 0 || maxCombinedOutputBytes > 2 * MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "combined output budget must be 0 (disabled) or at most 128 MiB");
        }

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must not exceed " + MAX_TIMEOUT);
        }
        if (maxOutputBytes < 1 || maxOutputBytes > MAX_OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "maxOutputBytes must be between 1 and " + MAX_OUTPUT_BYTES);
        }
        boolean workingDirectoryVisible =
                readableRoots.stream().anyMatch(workingDirectory::startsWith)
                        || writableRoots.stream().anyMatch(workingDirectory::startsWith);
        if (readPolicy == ReadPolicy.DECLARED_ONLY && !workingDirectoryVisible) {
            throw new IllegalArgumentException(
                    "workingDirectory must be inside a readable or writable root");
        }
        requirePathLimit("readable roots", readableRoots);
        requirePathLimit("writable roots", writableRoots);
        requirePathLimit("protected paths", protectedPaths);
        for (Path protectedPath : protectedPaths) {
            if (writableRoots.stream().noneMatch(protectedPath::startsWith)) {
                throw new IllegalArgumentException(
                        "protected path must be inside a writable root: " + protectedPath);
            }
        }
    }

    public static Builder builder(Path workingDirectory) {
        return new Builder(workingDirectory);
    }

    public SandboxResourceLimits resourceLimits() {
        return resourceLimits;
    }

    public SandboxIsolationLevel requiredIsolation() {
        return requiredIsolation;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public List<Path> writableRoots() {
        return writableRoots;
    }

    public List<Path> readableRoots() {
        return readableRoots;
    }

    public List<Path> protectedPaths() {
        return protectedPaths;
    }

    public NetworkPolicy networkPolicy() {
        return networkPolicy;
    }

    public ReadPolicy readPolicy() {
        return readPolicy;
    }

    public DeletionPolicy deletionPolicy() {
        return deletionPolicy;
    }

    public Duration timeout() {
        return timeout;
    }

    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    /**
     * Whether the outer sandbox launcher may resolve argv[0] through PATH. Disabled by default so a
     * production caller must identify the trusted interpreter or executable explicitly.
     */
    public boolean allowPathSearch() {
        return allowPathSearch;
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private static List<Path> immutableNormalized(List<Path> paths) {
        List<Path> result = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path normalized = normalize(path);
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static void requirePathLimit(String description, List<Path> paths) {
        if (paths.size() > MAX_PATHS_PER_CATEGORY) {
            throw new IllegalArgumentException(
                    description + " exceeds limit " + MAX_PATHS_PER_CATEGORY);
        }
    }

    public OutputLimitAction onOutputLimit() {
        return onOutputLimit;
    }

    /** Zero disables the shared stdout/stderr budget; individual budgets always apply. */
    public int maxCombinedOutputBytes() {
        return maxCombinedOutputBytes;
    }

    public OutputCallbackMode outputCallbackMode() {
        return outputCallbackMode;
    }

    public static final class Builder {

        private SandboxResourceLimits resourceLimits = SandboxResourceLimits.none();
        private SandboxIsolationLevel requiredIsolation = SandboxIsolationLevel.UNKNOWN;
        private final Path workingDirectory;
        private final List<Path> readableRoots = new ArrayList<>();
        private final List<Path> writableRoots = new ArrayList<>();
        private final List<Path> protectedPaths = new ArrayList<>();
        private NetworkPolicy networkPolicy = defaultNetworkPolicy();
        private ReadPolicy readPolicy = defaultReadPolicy();
        private DeletionPolicy deletionPolicy = DeletionPolicy.ALLOW;
        private Duration timeout = DEFAULT_TIMEOUT;
        private int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        private boolean allowPathSearch;
        private OutputLimitAction onOutputLimit = OutputLimitAction.TRUNCATE;
        private int maxCombinedOutputBytes;
        private OutputCallbackMode outputCallbackMode = OutputCallbackMode.RAW;

        private Builder(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            this.readableRoots.add(workingDirectory);
            this.writableRoots.add(workingDirectory);
        }

        private static NetworkPolicy defaultNetworkPolicy() {
            return SandboxPlatform.current() == SandboxPlatform.WINDOWS
                    ? NetworkPolicy.ALLOW
                    : NetworkPolicy.DENY;
        }

        private static ReadPolicy defaultReadPolicy() {
            return SandboxPlatform.current() == SandboxPlatform.WINDOWS
                    ? ReadPolicy.HOST
                    : ReadPolicy.DECLARED_ONLY;
        }

        public Builder readableRoot(Path root) {
            readableRoots.add(Objects.requireNonNull(root, "root"));
            return this;
        }

        public Builder writableRoot(Path root) {
            Path required = Objects.requireNonNull(root, "root");
            writableRoots.add(required);
            readableRoots.add(required);
            return this;
        }

        /**
         * Removes the builder's implicit write grant for the working directory. If no narrower
         * writable root is added, the resulting policy is read-only except for the SDK-managed
         * private temporary directory.
         */
        public Builder readOnlyWorkingDirectory() {
            Path normalizedWorkingDirectory = normalize(workingDirectory);
            writableRoots.removeIf(path -> normalize(path).equals(normalizedWorkingDirectory));
            return this;
        }

        public Builder protect(Path path) {
            protectedPaths.add(Objects.requireNonNull(path, "path"));
            return this;
        }

        public Builder network(NetworkPolicy policy) {
            this.networkPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder readPolicy(ReadPolicy policy) {
            this.readPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Controls deletion and rename independently from create/in-place-write access. Backends
         * that cannot enforce {@link DeletionPolicy#DENY} reject the request before execution.
         */
        public Builder deletion(DeletionPolicy policy) {
            this.deletionPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder maxOutputBytes(int maxOutputBytes) {
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        public Builder allowPathSearch(boolean allowPathSearch) {
            this.allowPathSearch = allowPathSearch;
            return this;
        }

        public Builder onOutputLimit(OutputLimitAction action) {
            this.onOutputLimit = Objects.requireNonNull(action, "action");
            return this;
        }

        public Builder maxCombinedOutputBytes(int bytes) {
            if (bytes < 1) {
                throw new IllegalArgumentException("combined output budget must be positive");
            }
            this.maxCombinedOutputBytes = bytes;
            return this;
        }

        public Builder outputCallbackMode(OutputCallbackMode mode) {
            this.outputCallbackMode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder resourceLimits(SandboxResourceLimits limits) {
            this.resourceLimits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public Builder requiredIsolation(SandboxIsolationLevel level) {
            this.requiredIsolation = Objects.requireNonNull(level, "level");
            return this;
        }

        public SandboxPolicy build() {
            return new SandboxPolicy(this);
        }
    }
}
