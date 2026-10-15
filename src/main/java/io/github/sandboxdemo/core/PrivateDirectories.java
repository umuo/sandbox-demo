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

    /** Windows 的 Java fileKey 可能为空，使用原生卷号及 128 位文件 ID。 */
    public static Object identity(Path path) throws IOException {
        if (OperatingSystem.current() != OperatingSystem.WINDOWS) {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                    .fileKey();
        }
        com.sun.jna.platform.win32.Kernel32 api = com.sun.jna.platform.win32.Kernel32.INSTANCE;
        // FILE_READ_ATTRIBUTES, share read/write/delete, OPEN_EXISTING,
        // FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT.
        com.sun.jna.platform.win32.WinNT.HANDLE handle =
                api.CreateFile(path.toString(), 0x80, 7, null, 3, 0x02000000 | 0x00200000, null);
        if (handle == null
                || com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            throw new IOException("failed to open workspace identity handle");
        }
        try {
            com.sun.jna.platform.win32.WinBase.FILE_ID_INFO info =
                    new com.sun.jna.platform.win32.WinBase.FILE_ID_INFO();
            if (!api.GetFileInformationByHandleEx(
                    handle,
                    com.sun.jna.platform.win32.WinBase.FileIdInfo,
                    info.getPointer(),
                    new com.sun.jna.platform.win32.WinDef.DWORD(info.size()))) {
                throw new IOException("failed to inspect native workspace identity");
            }
            return Base64.getEncoder()
                    .encodeToString(info.getPointer().getByteArray(0, info.size()));
        } finally {
            if (!api.CloseHandle(handle)) {
                throw new IOException("failed to close workspace identity handle");
            }
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
