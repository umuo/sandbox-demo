package io.github.sandboxdemo.platform.macos;

import io.github.sandboxdemo.api.NetworkPolicy;
import io.github.sandboxdemo.api.ReadPolicy;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.util.ArrayList;
import java.util.List;

/** Generates an SBPL profile while passing all paths as sandbox-exec parameters. */
final class MacOsSeatbeltProfile {

    private static final List<String> RUNTIME_ROOTS =
            io.github.sandboxdemo.core.Java8.listOf(
                    "/System",
                    "/usr",
                    "/bin",
                    "/sbin",
                    "/private/var/select/sh",
                    "/private/etc/passwd",
                    "/private/etc/group",
                    "/private/etc/hosts",
                    "/private/etc/resolv.conf",
                    "/private/etc/ssl",
                    "/private/var/db/timezone",
                    "/dev");

    private static final String BASE_PROFILE =
            io.github.sandboxdemo.core.Java8.lines(
                    "(version 1)",
                    "(deny default)",
                    "",
                    "; The policy is inherited by descendants.",
                    "; dyld ignition reads the root directory itself, never its descendants.",
                    "(allow file-read-data (require-all (literal \"/\") (vnode-type DIRECTORY)))",
                    "(allow process-exec)",
                    "(allow process-fork)",
                    "(allow signal (target same-sandbox))",
                    "(allow process-info* (target same-sandbox))",
                    "",
                    "(allow file-write-data",
                    "  (require-all (literal \"/dev/null\") (vnode-type CHARACTER-DEVICE)))",
                    "",
                    "; Runtime discovery only. Avoid blanket sysctl and Mach access.",
                    "(allow sysctl-read",
                    "  (sysctl-name \"hw.activecpu\")",
                    "  (sysctl-name \"hw.byteorder\")",
                    "  (sysctl-name \"hw.cpufamily\")",
                    "  (sysctl-name \"hw.cputype\")",
                    "  (sysctl-name \"hw.logicalcpu\")",
                    "  (sysctl-name \"hw.logicalcpu_max\")",
                    "  (sysctl-name \"hw.machine\")",
                    "  (sysctl-name \"hw.memsize\")",
                    "  (sysctl-name \"hw.ncpu\")",
                    "  (sysctl-name \"hw.pagesize\")",
                    "  (sysctl-name \"hw.pagesize_compat\")",
                    "  (sysctl-name \"hw.physicalcpu\")",
                    "  (sysctl-name \"hw.physicalcpu_max\")",
                    "  (sysctl-name-prefix \"hw.optional.arm.\")",
                    "  (sysctl-name-prefix \"hw.optional.armv8_\")",
                    "  (sysctl-name \"kern.argmax\")",
                    "  (sysctl-name \"kern.hostname\")",
                    "  (sysctl-name \"kern.maxfilesperproc\")",
                    "  (sysctl-name \"kern.maxproc\")",
                    "  (sysctl-name \"kern.osproductversion\")",
                    "  (sysctl-name \"kern.osrelease\")",
                    "  (sysctl-name \"kern.ostype\")",
                    "  (sysctl-name \"kern.osversion\")",
                    "  (sysctl-name \"kern.version\")",
                    "  (sysctl-name \"machdep.cpu.brand_string\")",
                    "  (sysctl-name \"vm.loadavg\"))",
                    "(allow sysctl-write (sysctl-name \"kern.grade_cputype\"))",
                    "",
                    "(allow iokit-open (iokit-registry-entry-class \"RootDomainUserClient\"))",
                    "(allow mach-lookup",
                    "  (global-name \"com.apple.system.opendirectoryd.libinfo\")",
                    "  (global-name \"com.apple.PowerManagement.control\"))",
                    "(allow ipc-posix-sem)",
                    "(allow pseudo-tty)",
                    "(allow file-read* file-write* file-ioctl (literal \"/dev/ptmx\"))",
                    "(allow file-read* file-write*",
                    "  (require-all",
                    "    (regex #\"^/dev/ttys[0-9]+\")",
                    "    (extension \"com.apple.sandbox.pty\")))",
                    "(allow file-ioctl (regex #\"^/dev/ttys[0-9]+\"))");

    private MacOsSeatbeltProfile() {}

    static void validateExecutableReadAccess(java.nio.file.Path executable, ValidatedPolicy policy)
            throws io.github.sandboxdemo.api.SandboxException {
        if (policy.readPolicy() == ReadPolicy.HOST
                || policy.readableRoots().stream().anyMatch(executable::startsWith)
                || RUNTIME_ROOTS.stream()
                        .map(java.nio.file.Paths::get)
                        .anyMatch(executable::startsWith)) {
            return;
        }
        throw new io.github.sandboxdemo.api.SandboxException(
                io.github.sandboxdemo.api.SandboxErrorCode.POLICY_INVALID,
                io.github.sandboxdemo.api.SandboxPhase.PREPARATION,
                "executable is outside effective readable roots: " + executable);
    }

    static GeneratedProfile generate(ValidatedPolicy policy) {
        StringBuilder profile = new StringBuilder(BASE_PROFILE);
        List<String> definitions = new ArrayList<>();

        if (policy.readPolicy() == ReadPolicy.HOST) {
            profile.append("\n(allow file-read*)\n");
        } else {
            profile.append("\n(allow file-read*\n");
            for (String runtimeRoot : RUNTIME_ROOTS) {
                profile.append("  (subpath \"").append(runtimeRoot).append("\")\n");
            }
            for (int i = 0; i < policy.readableRoots().size(); i++) {
                String name = "READABLE_" + i;
                profile.append("  (subpath (param \"").append(name).append("\"))\n");
                definitions.add("-D" + name + "=" + policy.readableRoots().get(i));
            }
            profile.append(")\n");
            // realpath/getcwd 需要查询已声明路径的祖先；仅元数据，不开放目录内容或文件数据。
            java.util.Set<String> ancestors = new java.util.TreeSet<>();
            ancestors.add("/");
            ancestors.add("/var");
            ancestors.add("/etc");
            ancestors.add("/tmp");
            List<java.nio.file.Path> paths = new ArrayList<>(policy.readableRoots());
            paths.addAll(policy.writableRoots());
            for (String runtimeRoot : RUNTIME_ROOTS) {
                paths.add(java.nio.file.Paths.get(runtimeRoot));
            }
            for (java.nio.file.Path path : paths) {
                for (java.nio.file.Path parent = path.getParent();
                        parent != null;
                        parent = parent.getParent()) {
                    ancestors.add(parent.toString());
                }
            }
            profile.append("(allow file-read-metadata\n");
            int index = 0;
            for (String ancestor : ancestors) {
                String name = "METADATA_" + index++;
                profile.append("  (literal (param \"").append(name).append("\"))\n");
                definitions.add("-D" + name + "=" + ancestor);
            }
            profile.append(")\n");
        }

        if (!policy.writableRoots().isEmpty()) {
            profile.append("\n(allow file-write*\n");
            for (int i = 0; i < policy.writableRoots().size(); i++) {
                String name = "WRITABLE_" + i;
                profile.append("  (subpath (param \"").append(name).append("\"))\n");
                definitions.add("-D" + name + "=" + policy.writableRoots().get(i));
            }
            profile.append(")\n");
        }

        for (int i = 0; i < policy.protectedPaths().size(); i++) {
            String name = "PROTECTED_" + i;
            profile.append("(deny file-write* (subpath (param \"").append(name).append("\")))\n");
            definitions.add("-D" + name + "=" + policy.protectedPaths().get(i));
        }

        if (policy.networkPolicy() == NetworkPolicy.ALLOW) {
            profile.append("(allow network-outbound)\n")
                    .append("(allow network-inbound)\n")
                    .append("(allow system-socket)\n");
        }
        return new GeneratedProfile(
                profile.toString(), io.github.sandboxdemo.core.Java8.copyList(definitions));
    }

    static final class GeneratedProfile {

        private final String profile;
        private final List<String> definitions;

        GeneratedProfile(String profile, List<String> definitions) {
            this.profile = profile;
            this.definitions = definitions;
        }

        String profile() {
            return profile;
        }

        List<String> definitions() {
            return definitions;
        }
    }
}
