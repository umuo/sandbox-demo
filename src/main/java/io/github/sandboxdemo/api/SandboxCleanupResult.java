package io.github.sandboxdemo.api;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 进程、管道、临时目录和宿主准备资源的独立回收快照。 */
public final class SandboxCleanupResult {
    private final SandboxCleanupState processes;
    private final SandboxCleanupState streams;
    private final SandboxCleanupState temporaryFiles;
    private final SandboxCleanupState hostResources;
    private final boolean workerFinished;
    private final Map<SandboxHostResource, SandboxCleanupState> hostDetails;

    public SandboxCleanupResult(
            SandboxCleanupState processes,
            SandboxCleanupState streams,
            SandboxCleanupState temporaryFiles,
            SandboxCleanupState hostResources,
            boolean workerFinished) {
        this(
                processes,
                streams,
                temporaryFiles,
                hostResources,
                workerFinished,
                Collections.emptyMap());
    }

    /** Detailed observations never override failed/unknown aggregate cleanup guarantees. */
    public SandboxCleanupResult(
            SandboxCleanupState processes,
            SandboxCleanupState streams,
            SandboxCleanupState temporaryFiles,
            SandboxCleanupState hostResources,
            boolean workerFinished,
            Map<SandboxHostResource, SandboxCleanupState> hostDetails) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.temporaryFiles = Objects.requireNonNull(temporaryFiles, "temporaryFiles");
        Map<SandboxHostResource, SandboxCleanupState> copy =
                new EnumMap<>(SandboxHostResource.class);
        for (Map.Entry<SandboxHostResource, SandboxCleanupState> entry :
                Objects.requireNonNull(hostDetails, "hostDetails").entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "host resource"),
                    Objects.requireNonNull(entry.getValue(), "host state"));
        }
        SandboxCleanupState aggregate = Objects.requireNonNull(hostResources, "hostResources");
        for (SandboxCleanupState state : copy.values()) {
            if (state == SandboxCleanupState.FAILED) {
                aggregate = SandboxCleanupState.FAILED;
                break;
            }
            if (state == SandboxCleanupState.UNKNOWN && aggregate != SandboxCleanupState.FAILED) {
                aggregate = SandboxCleanupState.UNKNOWN;
            }
        }
        this.hostResources = aggregate;
        this.hostDetails = Collections.unmodifiableMap(copy);
        this.workerFinished = workerFinished;
    }

    public static SandboxCleanupResult unknown() {
        return new SandboxCleanupResult(
                SandboxCleanupState.UNKNOWN,
                SandboxCleanupState.UNKNOWN,
                SandboxCleanupState.UNKNOWN,
                SandboxCleanupState.UNKNOWN,
                false);
    }

    public SandboxCleanupState processes() {
        return processes;
    }

    public SandboxCleanupState streams() {
        return streams;
    }

    public SandboxCleanupState temporaryFiles() {
        return temporaryFiles;
    }

    public SandboxCleanupState hostResources() {
        return hostResources;
    }

    public boolean workerFinished() {
        return workerFinished;
    }

    /** Immutable named checks. Missing entries are unobserved, not successful cleanup. */
    public Map<SandboxHostResource, SandboxCleanupState> hostDetails() {
        return hostDetails;
    }

    public boolean confirmed() {
        return workerFinished
                && known(processes)
                && known(streams)
                && known(temporaryFiles)
                && known(hostResources);
    }

    private static boolean known(SandboxCleanupState state) {
        return state == SandboxCleanupState.CONFIRMED || state == SandboxCleanupState.NOT_REQUIRED;
    }

    @Override
    public String toString() {
        return "SandboxCleanupResult[processes="
                + processes
                + ", streams="
                + streams
                + ", temporaryFiles="
                + temporaryFiles
                + ", hostResources="
                + hostResources
                + ", workerFinished="
                + workerFinished
                + "]";
    }
}
