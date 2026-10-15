package io.github.sandboxdemo.api;

import java.nio.file.Path;
import java.util.*;

/** 宿主控制的目标环境配置。严格模式仅使用固定工具目录和显式允许的变量。 工具目录必须由宿主供应并保护，不能来自模型参数。 */
public final class SandboxEnvironment {
    private final boolean strict;
    private final List<Path> toolchainDirectories;
    private final Set<String> allowedVariables;

    private SandboxEnvironment(boolean strict, List<Path> tools, Set<String> variables) {
        this.strict = strict;
        this.toolchainDirectories = Collections.unmodifiableList(new ArrayList<>(tools));
        this.allowedVariables = Collections.unmodifiableSet(new TreeSet<>(variables));
    }

    public static SandboxEnvironment legacy() {
        return new SandboxEnvironment(false, Collections.emptyList(), Collections.emptySet());
    }

    /** 所有 PATH 项必须是宿主指定的绝对目录；不会补充宿主 PATH。 */
    public static SandboxEnvironment strict(Path... directories) {
        if (directories.length == 0 || directories.length > 64) {
            throw new IllegalArgumentException(
                    "strict environment requires 1 to 64 tool directories");
        }
        List<Path> tools = new ArrayList<>();
        for (Path path : directories) {
            if (!Objects.requireNonNull(path, "tool directory").isAbsolute()
                    || path.toString().indexOf(java.io.File.pathSeparatorChar) >= 0) {
                throw new IllegalArgumentException(
                        "tool directories must be absolute PATH entries");
            }
            Path normalized = path.normalize();
            if (!tools.contains(normalized)) {
                tools.add(normalized);
            }
        }
        return new SandboxEnvironment(true, tools, Collections.emptySet());
    }

    /** 显式允许应用变量；PATH、目录变量及加载器/运行时控制变量始终拒绝。 */
    public SandboxEnvironment allowVariable(String name) {
        String canonical = canonicalName(name);
        if (!strict || controlledVariable(canonical)) {
            throw new IllegalArgumentException("variable cannot be allowed in strict environment");
        }
        Set<String> names = new TreeSet<>(allowedVariables);
        names.add(canonical);
        return new SandboxEnvironment(true, toolchainDirectories, names);
    }

    public static String canonicalName(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid strict environment variable name");
        }
        return name.toUpperCase(Locale.ROOT);
    }

    /** 默认拒绝整类注入入口，新增应用变量不能绕过此规则。 */
    public static boolean controlledVariable(String name) {
        String key = canonicalName(name);
        return Arrays.asList(
                                "PATH",
                                "PATHEXT",
                                "SYSTEMROOT",
                                "WINDIR",
                                "COMSPEC",
                                "TEMP",
                                "TMP",
                                "TMPDIR",
                                "HOME",
                                "USERPROFILE",
                                "XDG_CACHE_HOME",
                                "ENV",
                                "BASH_ENV",
                                "SHELLOPTS",
                                "BASHOPTS",
                                "IFS",
                                "CDPATH",
                                "GCONV_PATH",
                                "LOCPATH",
                                "NLSPATH",
                                "CLASSPATH",
                                "JAVA_TOOL_OPTIONS",
                                "JDK_JAVA_OPTIONS",
                                "_JAVA_OPTIONS")
                        .contains(key)
                || key.startsWith("LD_")
                || key.startsWith("DYLD_")
                || key.startsWith("JAVA_")
                || key.startsWith("JDK_")
                || key.startsWith("PYTHON")
                || key.startsWith("NODE_")
                || key.startsWith("RUBY")
                || key.startsWith("PERL")
                || key.startsWith("BASH_FUNC_")
                || key.startsWith("DOTNET_")
                || key.startsWith("COMPLUS_")
                || key.startsWith("GIT_");
    }

    public boolean isStrict() {
        return strict;
    }

    public List<Path> toolchainDirectories() {
        return toolchainDirectories;
    }

    public Set<String> allowedVariables() {
        return allowedVariables;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SandboxEnvironment)) {
            return false;
        }
        SandboxEnvironment that = (SandboxEnvironment) other;
        return strict == that.strict
                && toolchainDirectories.equals(that.toolchainDirectories)
                && allowedVariables.equals(that.allowedVariables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(strict, toolchainDirectories, allowedVariables);
    }

    @Override
    public String toString() {
        return "SandboxEnvironment[strict="
                + strict
                + ", toolDirectories="
                + toolchainDirectories.size()
                + ", allowedVariables="
                + allowedVariables.size()
                + "]";
    }
}
