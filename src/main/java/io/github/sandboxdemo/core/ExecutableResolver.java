package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves argv[0] before sandbox entry and avoids implicit current-directory search. */
public final class ExecutableResolver {

    private ExecutableResolver() {}

    public static Path resolve(
            String executable,
            Path workingDirectory,
            Map<String, String> environment,
            boolean allowPathSearch)
            throws SandboxException {

        Path requested = Path.of(executable);
        if (requested.isAbsolute()) {
            return requireExecutable(requested);
        }
        if (executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
            return requireExecutable(workingDirectory.resolve(requested));
        }

        if (!allowPathSearch) {
            throw new SandboxException(
                    "bare executable names are disabled; pass an absolute path or explicitly "
                            + "enable PATH search: "
                            + executable);
        }

        String pathValue = environment.getOrDefault("PATH", System.getenv("PATH"));
        if (pathValue == null || pathValue.isBlank()) {
            throw new SandboxException("PATH is empty; cannot resolve executable: " + executable);
        }

        List<String> names = candidateNames(executable, environment);
        for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            Path directory = Path.of(entry).toAbsolutePath().normalize();
            if (directory.equals(workingDirectory)) {
                continue;
            }
            for (String name : names) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate) && isExecutable(candidate)) {
                    return realPath(candidate);
                }
            }
        }
        throw new SandboxException(
                "executable was not found on trusted PATH entries: " + executable);
    }

    private static List<String> candidateNames(String executable, Map<String, String> environment) {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS || executable.contains(".")) {
            return List.of(executable);
        }
        String pathExt = environment.getOrDefault("PATHEXT", ".COM;.EXE;.BAT;.CMD");
        List<String> result = new ArrayList<>();
        result.add(executable);
        for (String extension : pathExt.split(";")) {
            if (!extension.isBlank()) {
                result.add(executable + extension.toLowerCase(Locale.ROOT));
                result.add(executable + extension.toUpperCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static Path requireExecutable(Path path) throws SandboxException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !isExecutable(normalized)) {
            throw new SandboxException(
                    "executable does not exist or is not executable: " + normalized);
        }
        return realPath(normalized);
    }

    private static boolean isExecutable(Path path) {
        return OperatingSystem.current() == OperatingSystem.WINDOWS || Files.isExecutable(path);
    }

    private static Path realPath(Path path) throws SandboxException {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new SandboxException("failed to resolve executable: " + path, e);
        }
    }
}
