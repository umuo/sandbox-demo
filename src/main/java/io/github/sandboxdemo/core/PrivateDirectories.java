package io.github.sandboxdemo.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 宿主私有目录创建：POSIX 原子 0700；Windows 在交付前固定当前宿主所有者 DACL。 */
public final class PrivateDirectories {
    private PrivateDirectories() {}

    public static Path create(Path parent, String prefix) throws IOException {
        Path canonical = parent.toRealPath();
        if (!Files.isDirectory(canonical)) {
            throw new IOException("private parent is not a directory");
        }
        if (Files.getFileStore(canonical).supportsFileAttributeView("posix")) {
            return Files.createTempDirectory(
                    canonical,
                    prefix,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
        Path directory = Files.createTempDirectory(canonical, prefix);
        try {
            AclFileAttributeView view =
                    Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (view == null) {
                throw new IOException("private directory permissions are unsupported");
            }
            UserPrincipal owner =
                    directory
                            .getFileSystem()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
            AclEntry entry =
                    AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                            .build();
            view.setAcl(Collections.singletonList(entry));
            for (AclEntry actual : view.getAcl()) {
                if (actual.type() == AclEntryType.ALLOW && !actual.principal().equals(owner)) {
                    throw new IOException("private directory inherited another allow principal");
                }
            }
            return directory;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Windows junction 和其他 reparse point 均视为链接，不能进入其目标。 */
    public static boolean isLink(Path path) throws IOException {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            return Files.isSymbolicLink(path);
        }
        int attributes =
                com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(path.toString());
        if (attributes == -1) {
            throw new IOException("failed to inspect directory reparse attributes");
        }
        return (attributes & 0x400) != 0;
    }

    /** 不跟随符号链接或 Windows junction；删除失败保守返回 false。 */
    public static boolean removeTree(Path root) {
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path path, BasicFileAttributes attrs) throws IOException {
                            if (isLink(path)) {
                                Files.delete(path);
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                                throws IOException {
                            Files.delete(path);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path path, IOException error)
                                throws IOException {
                            if (error != null) {
                                throw error;
                            }
                            Files.delete(path);
                            return FileVisitResult.CONTINUE;
                        }
                    });
            return Files.notExists(root, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return false;
        }
    }
}
