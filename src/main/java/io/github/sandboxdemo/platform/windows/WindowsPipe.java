package io.github.sandboxdemo.platform.windows;

import static io.github.sandboxdemo.platform.windows.WindowsNative.Kernel32;

import com.sun.jna.Pointer;

/** 管道由实际 I/O 线程关闭；监督线程只请求取消，避免关闭后复用句柄的竞态。 */
final class WindowsPipe implements AutoCloseable {
    private Pointer handle;

    WindowsPipe(Pointer handle) {
        this.handle = handle;
    }

    synchronized Pointer handle() {
        return handle;
    }

    synchronized void cancel() {
        if (handle != null) {
            Kernel32.INSTANCE.CancelIoEx(handle, null);
        }
    }

    @Override
    public synchronized void close() throws io.github.sandboxdemo.api.SandboxException {
        if (handle != null) {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw new io.github.sandboxdemo.api.SandboxException(
                        io.github.sandboxdemo.api.SandboxErrorCode.CLEANUP_FAILED,
                        io.github.sandboxdemo.api.SandboxPhase.CLEANUP,
                        "Windows pipe closure could not be confirmed");
            }
            handle = null;
        }
    }
}
