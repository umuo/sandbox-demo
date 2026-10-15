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

    public static Map<String, String> build(SandboxRequest request, ValidatedPolicy policy) {

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
