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
}
