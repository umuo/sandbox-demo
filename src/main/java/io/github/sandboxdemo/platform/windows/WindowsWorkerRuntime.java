package io.github.sandboxdemo.platform.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Advapi32Util;
import io.github.sandboxdemo.api.SandboxBackendUnavailableException;
import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the installed trusted Java runtime and worker JAR. */
final class WindowsWorkerRuntime {

    private static final String RUNTIME_DIRECTORY = "runtime";
    private static final String CLASSPATH_MANIFEST = "worker-classpath.txt";
    private static final int MAX_RUNTIME_JARS = 16;
    private static final List<Class<?>> REQUIRED_CLASSES =
            List.of(WindowsSandboxWorker.class, Native.class, Advapi32Util.class);

    private final Path javaExecutable;
    private final List<Path> classPath;

    private WindowsWorkerRuntime(Path javaExecutable, List<Path> classPath) {
        this.javaExecutable = javaExecutable;
        this.classPath = classPath;
    }

    static WindowsWorkerRuntime resolve(
            WindowsSandboxInstallation installation, ValidatedPolicy policy)
            throws SandboxException {
        try {
            Path javaHome = installation.javaHome().toRealPath();
            Path java = javaHome.resolve("bin").resolve("java.exe").toRealPath();
            if (!Files.isRegularFile(java)) {
                throw new SandboxBackendUnavailableException(
                        "Java worker executable is missing: " + java);
            }
            if (policy.writableRoots().stream().anyMatch(root -> overlaps(root, javaHome))) {
                throw new SandboxBackendUnavailableException(
                        "installed Java runtime must not overlap a writable root: " + javaHome);
            }
            List<Path> workerJars = installedClassPath(installation.home());
            for (Path workerJar : workerJars) {
                if (policy.writableRoots().stream().anyMatch(root -> overlaps(root, workerJar))) {
                    throw new SandboxBackendUnavailableException(
                            "installed Windows worker JAR must not overlap a writable root: "
                                    + workerJar);
                }
            }
            return new WindowsWorkerRuntime(java, workerJars);
        } catch (IOException e) {
            throw new SandboxException("failed to resolve the trusted Windows worker runtime", e);
        }
    }

    static void verifyInstalled(WindowsSandboxInstallation installation) throws SandboxException {
        try {
            Path java = installation.javaHome().toRealPath().resolve("bin").resolve("java.exe");
            if (!Files.isRegularFile(java)) {
                throw new SandboxBackendUnavailableException(
                        "installed Java worker executable is missing: " + java);
            }
            installedClassPath(installation.home());
        } catch (IOException e) {
            throw new SandboxException("failed to verify the installed Windows worker runtime", e);
        }
    }

    static List<Path> sourceRuntimeJars() throws SandboxException {
        Set<Path> result = new LinkedHashSet<>();
        try {
            for (Class<?> requiredClass : REQUIRED_CLASSES) {
                if (requiredClass.getProtectionDomain().getCodeSource() == null) {
                    throw new SandboxBackendUnavailableException(
                            "cannot locate packaged runtime for " + requiredClass.getName());
                }
                Path source =
                        Path.of(
                                        requiredClass
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI())
                                .toAbsolutePath()
                                .normalize()
                                .toRealPath();
                if (!Files.isRegularFile(source)
                        || !source.getFileName().toString().toLowerCase().endsWith(".jar")) {
                    throw new SandboxBackendUnavailableException(
                            "Windows setup requires packaged SDK dependencies, not an exploded "
                                    + "classes directory: "
                                    + source);
                }
                requireRootClassEntry(source, requiredClass);
                result.add(source);
            }
            return List.copyOf(result);
        } catch (java.net.URISyntaxException | IOException e) {
            throw new SandboxException("failed to resolve packaged Windows worker JARs", e);
        }
    }

    private static void requireRootClassEntry(Path jar, Class<?> requiredClass)
            throws IOException, SandboxBackendUnavailableException {
        String entry = requiredClass.getName().replace('.', '/') + ".class";
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(jar.toFile())) {
            if (archive.getEntry(entry) == null) {
                throw new SandboxBackendUnavailableException(
                        "unsupported nested-JAR layout for Windows worker class "
                                + requiredClass.getName()
                                + "; run setup from the SDK all.jar CLI instead: "
                                + jar);
            }
        }
    }

    static Path runtimeDirectory(Path home) {
        return home.resolve(RUNTIME_DIRECTORY);
    }

    static Path classPathManifest(Path home) {
        return runtimeDirectory(home).resolve(CLASSPATH_MANIFEST);
    }

    static void writeChecksum(Path workerJar) throws SandboxException {
        try {
            String value = sha256(workerJar) + System.lineSeparator();
            Path checksum = checksumFile(workerJar);
            Path temporary = Files.createTempFile(checksum.getParent(), "worker-sha256-", ".tmp");
            Files.writeString(temporary, value, java.nio.charset.StandardCharsets.US_ASCII);
            try {
                Files.move(
                        temporary,
                        checksum,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, checksum, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new SandboxException("failed to write the Windows worker checksum", e);
        }
    }

    private static void verifyChecksum(Path workerJar) throws SandboxException {
        Path checksum = checksumFile(workerJar);
        try {
            String expected = Files.readString(checksum).trim();
            String actual = sha256(workerJar);
            if (!MessageDigest.isEqual(
                    expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new SandboxException(
                        "installed Windows worker checksum does not match setup metadata");
            }
        } catch (IOException e) {
            throw new SandboxException("failed to verify the installed Windows worker", e);
        }
    }

    private static Path checksumFile(Path workerJar) {
        return workerJar.resolveSibling(workerJar.getFileName() + ".sha256");
    }

    static String sha256Hex(Path path) throws SandboxException {
        try {
            return sha256(path);
        } catch (IOException e) {
            throw new SandboxException("failed to hash Windows worker runtime: " + path, e);
        }
    }

    private static List<Path> installedClassPath(Path home) throws SandboxException, IOException {
        Path runtime = runtimeDirectory(home).toRealPath();
        Path manifest = classPathManifest(home);
        if (!Files.isRegularFile(manifest)) {
            throw new SandboxBackendUnavailableException(
                    "installed Windows worker classpath is missing: " + manifest);
        }
        List<String> entries =
                Files.readAllLines(manifest, java.nio.charset.StandardCharsets.US_ASCII);
        if (entries.isEmpty() || entries.size() > MAX_RUNTIME_JARS) {
            throw new SandboxException("invalid Windows worker classpath entry count");
        }
        List<Path> result = new ArrayList<>(entries.size());
        for (String entry : entries) {
            if (!entry.matches("[A-Za-z0-9._-]+\\.jar")) {
                throw new SandboxException("invalid Windows worker classpath entry: " + entry);
            }
            Path workerJar = runtime.resolve(entry).toRealPath();
            if (!workerJar.getParent().equals(runtime) || !Files.isRegularFile(workerJar)) {
                throw new SandboxException(
                        "Windows worker classpath escaped its runtime directory: " + entry);
            }
            verifyChecksum(workerJar);
            result.add(workerJar);
        }
        return List.copyOf(result);
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static String sha256(Path path) throws IOException, SandboxException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new SandboxException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }

    Path javaExecutable() {
        return javaExecutable;
    }

    String classPathArgument() {
        return classPath.stream()
                .map(Path::toString)
                .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }
}
