package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a small environment allowlist so host secrets are not inherited. */
public final class EnvironmentPolicy {

    private static final List<String> ALLOWED_HOST_VARIABLES =
            io.github.sandboxdemo.core.Java8.listOf(
                    "PATH",
                    "PATHEXT",
                    "SystemRoot",
                    "WINDIR",
                    "ComSpec",
                    "LANG",
                    "LC_ALL",
                    "TZ",
                    "TERM");

    private EnvironmentPolicy() {}

    /** Trusted probes never inherit caller loader/runtime injection variables or caller PATH. */
    public static Map<String, String> probe(ValidatedPolicy policy) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String name : ALLOWED_HOST_VARIABLES) {
            if (!"PATH".equals(name)) {
                String value = System.getenv(name);
                if (value != null) {
                    result.put(name, value);
                }
            }
        }
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        result.put(
                "PATH",
                io.github.sandboxdemo.api.SandboxPlatform.current()
                                == io.github.sandboxdemo.api.SandboxPlatform.WINDOWS
                        ? java.nio.file.Paths.get(root, "System32").toString()
                        : "/usr/bin:/bin");
        String temp = policy.privateTempDirectory().toString();
        result.put("TEMP", temp);
        result.put("TMP", temp);
        result.put("TMPDIR", temp);
        result.put("HOME", policy.workingDirectory().toString());
        result.put("USERPROFILE", policy.workingDirectory().toString());
        return io.github.sandboxdemo.core.Java8.copyMap(result);
    }

    /** 严格环境变量在准备资源或启动探针前准入，错误默认不携带变量名或值。 */
    public static void validate(SandboxRequest request)
            throws io.github.sandboxdemo.api.SandboxException {
        if (!request.executionEnvironment().isStrict()) {
            return;
        }
        for (String name : request.environment().keySet()) {
            String key;
            try {
                key = io.github.sandboxdemo.api.SandboxEnvironment.canonicalName(name);
            } catch (IllegalArgumentException invalid) {
                throw invalidEnvironment();
            }
            if (io.github.sandboxdemo.api.SandboxEnvironment.controlledVariable(key)
                    || !request.executionEnvironment().allowedVariables().contains(key)) {
                throw invalidEnvironment();
            }
        }
    }

    private static io.github.sandboxdemo.api.SandboxException invalidEnvironment() {
        return new io.github.sandboxdemo.api.SandboxException(
                io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                io.github.sandboxdemo.api.SandboxPhase.ADMISSION,
                "strict environment variable is not permitted");
    }

    /** 规范化宿主工具目录，并拒绝与目标可写根相交的路径。 */
    public static java.util.List<java.nio.file.Path> toolchains(
            SandboxRequest request, java.util.List<java.nio.file.Path> writable)
            throws io.github.sandboxdemo.api.SandboxException {
        java.util.List<java.nio.file.Path> tools = new java.util.ArrayList<>();
        try {
            java.nio.file.Path cwd = request.policy().workingDirectory().toRealPath();
            for (java.nio.file.Path supplied :
                    request.executionEnvironment().toolchainDirectories()) {
                java.nio.file.Path path = supplied.toRealPath();
                if (!java.nio.file.Files.isDirectory(path)
                        || path.toString().indexOf(java.io.File.pathSeparatorChar) >= 0
                        || path.startsWith(cwd)
                        || cwd.startsWith(path)) {
                    throw new java.io.IOException(
                            "tool directory overlaps workspace or is invalid");
                }
                for (java.nio.file.Path root : writable) {
                    if (path.startsWith(root) || root.startsWith(path)) {
                        throw new java.io.IOException("tool directory overlaps writable roots");
                    }
                }
                if (!tools.contains(path)) {
                    tools.add(path);
                }
            }
        } catch (java.io.IOException | RuntimeException invalid) {
            throw new io.github.sandboxdemo.api.SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "strict tool directory validation failed",
                    invalid);
        }
        return tools;
    }

    private static Map<String, String> strict(SandboxRequest request, ValidatedPolicy policy)
            throws io.github.sandboxdemo.api.SandboxException {
        Map<String, String> result = new LinkedHashMap<>();
        java.util.List<java.nio.file.Path> tools = toolchains(request, policy.writableRoots());
        StringBuilder path = new StringBuilder();
        for (java.nio.file.Path directory : tools) {
            if (path.length() > 0) {
                path.append(java.io.File.pathSeparator);
            }
            path.append(directory);
        }
        result.put("PATH", path.toString());
        if (io.github.sandboxdemo.api.SandboxPlatform.current()
                == io.github.sandboxdemo.api.SandboxPlatform.WINDOWS) {
            String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            result.put("SystemRoot", root);
            result.put("WINDIR", root);
            result.put("ComSpec", java.nio.file.Paths.get(root, "System32", "cmd.exe").toString());
            result.put("PATHEXT", ".EXE");
        }
        String temp = policy.privateTempDirectory().toString();
        result.put("TEMP", temp);
        result.put("TMP", temp);
        result.put("TMPDIR", temp);
        result.put("HOME", policy.privateTempDirectory().resolve("home").toString());
        result.put("USERPROFILE", policy.privateTempDirectory().resolve("home").toString());
        result.put("XDG_CACHE_HOME", policy.privateTempDirectory().resolve("cache").toString());
        request.environment().forEach(result::put);
        return io.github.sandboxdemo.core.Java8.copyMap(result);
    }

    public static java.nio.file.Path resolveExecutable(
            SandboxRequest request, ValidatedPolicy policy, Map<String, String> environment)
            throws io.github.sandboxdemo.api.SandboxException {
        java.nio.file.Path executable =
                ExecutableResolver.resolve(
                        request.command().executable(),
                        policy.workingDirectory(),
                        environment,
                        policy.allowPathSearch());
        if (request.executionEnvironment().isStrict()
                && toolchains(request, policy.writableRoots()).stream()
                        .noneMatch(executable::startsWith)) {
            throw new io.github.sandboxdemo.api.SandboxException(
                    io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                    io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                    "executable is outside fixed tool directories");
        }
        return executable;
    }

    public static Map<String, String> build(SandboxRequest request, ValidatedPolicy policy)
            throws io.github.sandboxdemo.api.SandboxException {
        validate(request);
        if (request.executionEnvironment().isStrict()) {
            return strict(request, policy);
        }

        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> host = System.getenv();
        for (String name : ALLOWED_HOST_VARIABLES) {
            String value = host.get(name);
            if (value != null) {
                result.put(name, value);
            }
        }

        String temp = policy.privateTempDirectory().toString();
        result.put("TEMP", temp);
        result.put("TMP", temp);
        result.put("TMPDIR", temp);
        result.put("HOME", policy.workingDirectory().toString());
        result.put("USERPROFILE", policy.workingDirectory().toString());
        result.put("XDG_CACHE_HOME", temp);
        request.environment()
                .forEach(
                        (name, value) -> {
                            result.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
                            result.put(name, value);
                        });
        return io.github.sandboxdemo.core.Java8.copyMap(result);
    }
}
