package io.github.sandboxdemo.core;

import io.github.sandboxdemo.api.SandboxRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a small environment allowlist so host secrets are not inherited. */
public final class EnvironmentPolicy {

    private static final List<String> ALLOWED_HOST_VARIABLES =
            List.of(
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
        return Map.copyOf(result);
    }
}
