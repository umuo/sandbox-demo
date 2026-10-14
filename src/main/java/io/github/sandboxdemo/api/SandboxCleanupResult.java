package io.github.sandboxdemo.api;

import java.util.Objects;

/** 进程、管道、临时目录和宿主准备资源的独立回收快照。 */
public final class SandboxCleanupResult {
    private final SandboxCleanupState processes;
    private final SandboxCleanupState streams;
    private final SandboxCleanupState temporaryFiles;
    private final SandboxCleanupState hostResources;
    private final boolean workerFinished;

    public SandboxCleanupResult(
            SandboxCleanupState processes,
            SandboxCleanupState streams,
            SandboxCleanupState temporaryFiles,
            SandboxCleanupState hostResources,
            boolean workerFinished) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.streams = Objects.requireNonNull(streams, "streams");
        this.temporaryFiles = Objects.requireNonNull(temporaryFiles, "temporaryFiles");
        this.hostResources = Objects.requireNonNull(hostResources, "hostResources");
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
