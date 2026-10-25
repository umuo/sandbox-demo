package io.github.sandboxdemo.platform.windows;

import com.sun.jna.Pointer;
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.core.ExecutionContext;
import java.util.function.BooleanSupplier;

/** Checked native cleanup; failures are retained while unrelated cleanup continues. */
final class WindowsCleanup {
    private static final ThreadLocal<java.util.function.Predicate<String>> FAULT =
            new ThreadLocal<>();

    private WindowsCleanup() {}

    static Check begin(SandboxHostResource resource) {
        return begin(resource, ExecutionContext.current());
    }

    static Check begin(SandboxHostResource resource, ExecutionContext context) {
        return new Check(context == null ? null : context.trackHost(resource));
    }

    static boolean run(SandboxHostResource resource, BooleanSupplier operation) {
        return run(resource, ExecutionContext.current(), operation);
    }

    static boolean run(
            SandboxHostResource resource, ExecutionContext context, BooleanSupplier operation) {
        Check check = begin(resource, context);
        boolean success = false;
        try {
            success = operation.getAsBoolean() && allowed(resource.name());
        } catch (RuntimeException | LinkageError failure) {
            /* other cleanup must continue */
        } finally {
            check.complete(success ? SandboxCleanupState.CONFIRMED : SandboxCleanupState.FAILED);
        }
        return success;
    }

    static boolean closeHandle(Pointer handle) {
        return closeHandle(handle, ExecutionContext.current());
    }

    static boolean closeHandle(Pointer handle, ExecutionContext context) {
        if (handle == null
                || Pointer.nativeValue(handle) == 0
                || Pointer.nativeValue(handle) == -1L) {
            return true;
        }
        return run(
                SandboxHostResource.NATIVE_HANDLES,
                context,
                () -> WindowsNative.Kernel32.INSTANCE.CloseHandle(handle));
    }

    static void localFree(Pointer memory) {
        if (memory != null) {
            run(
                    SandboxHostResource.NATIVE_ALLOCATIONS,
                    () -> WindowsNative.Kernel32.INSTANCE.LocalFree(memory) == null);
        }
    }

    // Package-private test seam: no environment or public API can enable native fault injection.
    // Cleanup faults downgrade the acknowledgement AFTER real cleanup, keeping the test host clean.
    static AutoCloseable inject(java.util.function.Predicate<String> success) {
        java.util.function.Predicate<String> previous = FAULT.get();
        FAULT.set(success);
        return () -> {
            if (previous == null) {
                FAULT.remove();
            } else {
                FAULT.set(previous);
            }
        };
    }

    static boolean allowed(String stage) {
        java.util.function.Predicate<String> fault = FAULT.get();
        boolean allowed = fault == null || fault.test(stage);
        if (!allowed) {
            com.sun.jna.Native.setLastError(5);
        }
        return allowed;
    }

    static final class Check {
        private final ExecutionContext.HostLease lease;

        Check(ExecutionContext.HostLease lease) {
            this.lease = lease;
        }

        void complete(SandboxCleanupState state) {
            if (lease != null) {
                lease.complete(state);
            }
        }
    }
}
