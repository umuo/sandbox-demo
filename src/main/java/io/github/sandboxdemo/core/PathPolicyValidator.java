package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxErrorCode;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.api.SandboxPhase;
import io.github.sandboxdemo.api.SandboxPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resolves policy paths immediately before entering an OS security boundary. */
public final class PathPolicyValidator {

    private PathPolicyValidator() {}

    public static ValidatedPolicy validate(SandboxPolicy policy) throws SandboxException {
        Path temp = null;
        try {
            Path cwd = requireDirectory(policy.workingDirectory(), "working directory");
            List<Path> readable = realPaths(policy.readableRoots(), true, "readable root");
            List<Path> writable = realPaths(policy.writableRoots(), true, "writable root");
            List<Path> protectedPaths = realPaths(policy.protectedPaths(), false, "protected path");
            rejectSymbolicLinkComponents(cwd, "resolved working directory");
            for (Path path : writable) {
                rejectSymbolicLinkComponents(path, "resolved writable root");
            }
            for (Path path : readable) {
                rejectSymbolicLinkComponents(path, "resolved readable root");
            }
            for (Path path : protectedPaths) {
                rejectSymbolicLinkComponents(path, "resolved protected path");
            }

            boolean workingDirectoryVisible =
                    readable.stream().anyMatch(cwd::startsWith)
                            || writable.stream().anyMatch(cwd::startsWith);
            if (policy.readPolicy() == io.github.sandboxdemo.api.ReadPolicy.DECLARED_ONLY
                    && !workingDirectoryVisible) {
                throw new SandboxException(
                        SandboxErrorCode.POLICY_INVALID,
                        SandboxPhase.PREPARATION,
                        "resolved working directory escaped readable/writable roots: " + cwd);
            }
            for (Path protectedPath : protectedPaths) {
                if (writable.stream().noneMatch(protectedPath::startsWith)) {
                    throw new SandboxException(
                            SandboxErrorCode.POLICY_INVALID,
                            SandboxPhase.PREPARATION,
                            "resolved protected path escaped writable roots: " + protectedPath);
                }
            }

            temp = Files.createTempDirectory("agent-sandbox-" + UUID.randomUUID() + '-');
            if (ExecutionContext.current() != null) {
                ExecutionContext.current().trackTemporary(temp);
            }
            setOwnerOnlyPermissions(temp);
            Path canonicalTemp = temp.toRealPath();
            if (!canonicalTemp.equals(temp) && ExecutionContext.current() != null) {
                ExecutionContext.current()
                        .temporaryCleaned(
                                temp, io.github.sandboxdemo.api.SandboxCleanupState.NOT_REQUIRED);
                ExecutionContext.current().trackTemporary(canonicalTemp);
            }
            temp = canonicalTemp;
            final Path effectiveTemp = temp;
            if (protectedPaths.stream().anyMatch(effectiveTemp::startsWith)) {
                cleanupDirectory(temp);
                throw new SandboxException(
                        SandboxErrorCode.POLICY_INVALID,
                        SandboxPhase.PREPARATION,
                        "private temp directory is inside a protected path: " + temp);
            }

            List<Path> effectiveReadable = new ArrayList<>(readable);
            effectiveReadable.add(temp);
            List<Path> effectiveWritable = new ArrayList<>(writable);
            effectiveWritable.add(temp);
            return new ValidatedPolicy(
                    cwd,
                    io.github.sandboxdemo.core.Java8.copyList(effectiveReadable),
                    io.github.sandboxdemo.core.Java8.copyList(effectiveWritable),
                    io.github.sandboxdemo.core.Java8.copyList(protectedPaths),
                    temp,
                    policy.networkPolicy(),
                    policy.readPolicy(),
                    policy.deletionPolicy(),
                    policy.timeout(),
                    policy.maxOutputBytes(),
                    policy.allowPathSearch());
        } catch (IOException e) {
            if (temp != null) {
                cleanupDirectory(temp);
            }
            throw new SandboxException(
                    SandboxErrorCode.POLICY_INVALID,
                    SandboxPhase.PREPARATION,
                    "failed to resolve sandbox paths",
                    e);
        }
    }

    /** Removes the per-execution temp directory without following symbolic links. */
    public static void cleanup(ValidatedPolicy policy) {
        cleanupWithResult(policy);
    }

    /** 返回临时目录删除证据，不跟随符号链接。 */
    public static io.github.sandboxdemo.api.SandboxCleanupState cleanupWithResult(
            ValidatedPolicy policy) {
        return cleanupDirectory(policy.privateTempDirectory());
    }

    private static void rejectSymbolicLinkComponents(Path input, String description)
            throws IOException, SandboxException {
        Path absolute = input.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new SandboxException(
                    SandboxErrorCode.POLICY_INVALID,
                    SandboxPhase.PREPARATION,
                    description + " has no filesystem root: " + input);
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new SandboxException(
                        SandboxErrorCode.POLICY_INVALID,
                        SandboxPhase.PREPARATION,
                        description + " contains a symbolic link: " + current);
            }
        }
    }

    private static void setOwnerOnlyPermissions(Path directory) throws IOException {
        try {
            Set<PosixFilePermission> permissions =
                    EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(directory, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows is protected later with an explicit DACL/token boundary.
        }
    }

    private static io.github.sandboxdemo.api.SandboxCleanupState cleanupDirectory(Path directory) {
        java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean();
        if (directory != null && !Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (IOException | SecurityException error) {
                                        failed.set(true);
                                    }
                                });
            } catch (IOException | java.io.UncheckedIOException | SecurityException error) {
                failed.set(true);
            }
        }
        if (directory != null && !Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            failed.set(true);
        }
        io.github.sandboxdemo.api.SandboxCleanupState state =
                failed.get()
                        ? io.github.sandboxdemo.api.SandboxCleanupState.FAILED
                        : io.github.sandboxdemo.api.SandboxCleanupState.CONFIRMED;
        if (ExecutionContext.current() != null) {
            ExecutionContext.current().temporaryCleaned(directory, state);
        }
        return state;
    }

    private static List<Path> realPaths(
            List<Path> paths, boolean requireDirectory, String description)
            throws IOException, SandboxException {

        List<Path> result = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path real = path.toRealPath();
            if (requireDirectory && !Files.isDirectory(real)) {
                throw new SandboxException(
                        SandboxErrorCode.POLICY_INVALID,
                        SandboxPhase.PREPARATION,
                        description + " is not a directory: " + real);
            }
            if (!result.contains(real)) {
                result.add(real);
            }
        }
        return result;
    }

    private static Path requireDirectory(Path path, String description)
            throws IOException, SandboxException {
        Path real = path.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new SandboxException(
                    SandboxErrorCode.POLICY_INVALID,
                    SandboxPhase.PREPARATION,
                    description + " is not a directory: " + real);
        }
        return real;
    }
}
