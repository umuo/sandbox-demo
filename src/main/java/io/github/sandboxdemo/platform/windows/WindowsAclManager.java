package io.github.sandboxdemo.platform.windows;

import io.github.sandboxdemo.api.SandboxException;
import io.github.sandboxdemo.core.ValidatedPolicy;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Applies short-lived NTFS capability ACEs and removes them after execution. */
final class WindowsAclManager {

    private final Path icacls;
    private final WindowsAclLedger ledger;

    WindowsAclManager() throws SandboxException {
        this(null);
    }

    WindowsAclManager(Path installationHome) throws SandboxException {
        String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        this.icacls = Path.of(systemRoot, "System32", "icacls.exe");
        if (!Files.isRegularFile(icacls)) {
            throw new SandboxException("icacls.exe was not found: " + icacls);
        }
        this.ledger = installationHome == null ? null : new WindowsAclLedger(installationHome);
    }

    WindowsAclLease apply(ValidatedPolicy policy) throws SandboxException, InterruptedException {
        return apply(policy, List.of());
    }

    WindowsAclLease apply(ValidatedPolicy policy, List<String> sandboxUserSids)
            throws SandboxException, InterruptedException {
        List<AclMutation> applied = new ArrayList<>();
        List<String> capabilitySids = new ArrayList<>();
        try {
            for (Path readableRoot : policy.readableRoots()) {
                boolean alreadyWritable =
                        policy.writableRoots().stream().anyMatch(readableRoot::startsWith);
                if (alreadyWritable) {
                    continue;
                }
                for (String userSid : sandboxUserSids) {
                    recordPersistentGrant(readableRoot, userSid);
                    grant(readableRoot, userSid, "(OI)(CI)(RX)", false);
                }
            }
            for (Path writableRoot : policy.writableRoots()) {
                String capabilitySid = WindowsCapabilitySid.random();
                grant(writableRoot, capabilitySid, "(OI)(CI)(M)", false);
                applied.add(AclMutation.grant(writableRoot, capabilitySid));
                capabilitySids.add(capabilitySid);

                for (String userSid : sandboxUserSids) {
                    recordPersistentGrant(writableRoot, userSid);
                    grant(writableRoot, userSid, "(OI)(CI)(M)", false);
                }
            }

            // Deny every write-related generic right and deletion on protected roots.
            for (Path protectedPath : policy.protectedPaths()) {
                for (String capabilitySid : capabilitySids) {
                    deny(protectedPath, capabilitySid, "(OI)(CI)(W,D)");
                    applied.add(AclMutation.deny(protectedPath, capabilitySid));
                }
            }
            return new WindowsAclLease(this, capabilitySids, applied);
        } catch (SandboxException | InterruptedException e) {
            rollback(applied);
            throw e;
        }
    }

    private void recordPersistentGrant(Path path, String sid) throws SandboxException {
        if (ledger != null) {
            ledger.record(path, sid);
        }
    }

    private void grant(Path path, String sid, String rights, boolean replace)
            throws SandboxException, InterruptedException {
        run(
                List.of(
                        icacls.toString(),
                        path.toString(),
                        replace ? "/grant:r" : "/grant",
                        "*" + sid + ':' + rights,
                        "/Q"));
    }

    private void deny(Path path, String sid, String rights)
            throws SandboxException, InterruptedException {
        run(List.of(icacls.toString(), path.toString(), "/deny", "*" + sid + ':' + rights, "/Q"));
    }

    private void rollback(List<AclMutation> mutations) {
        List<AclMutation> reverse = new ArrayList<>(mutations);
        Collections.reverse(reverse);
        for (AclMutation mutation : reverse) {
            try {
                remove(mutation);
            } catch (Exception ignored) {
                // A random capability SID left after a crash/rollback is inert because
                // no subsequent restricted token reuses it.
            }
        }
    }

    private void remove(AclMutation mutation) throws SandboxException, InterruptedException {
        run(
                List.of(
                        icacls.toString(),
                        mutation.path().toString(),
                        mutation.deny() ? "/remove:d" : "/remove:g",
                        "*" + mutation.sid(),
                        "/Q"));
    }

    private static void run(List<String> command) throws SandboxException, InterruptedException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new SandboxException(
                        "failed to configure NTFS sandbox ACL (exit="
                                + exitCode
                                + "): "
                                + new String(output, Charset.defaultCharset()).trim());
            }
        } catch (IOException e) {
            throw new SandboxException("failed to launch icacls.exe", e);
        }
    }

    static final class WindowsAclLease implements AutoCloseable {

        private final WindowsAclManager manager;
        private final List<String> capabilitySids;
        private final List<AclMutation> mutations;
        private boolean closed;

        private WindowsAclLease(
                WindowsAclManager manager,
                List<String> capabilitySids,
                List<AclMutation> mutations) {
            this.manager = manager;
            this.capabilitySids = List.copyOf(capabilitySids);
            this.mutations = List.copyOf(mutations);
        }

        List<String> capabilitySids() {
            return capabilitySids;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                manager.rollback(mutations);
            }
        }
    }

    private record AclMutation(Path path, String sid, boolean deny) {

        static AclMutation grant(Path path, String sid) {
            return new AclMutation(path, sid, false);
        }

        static AclMutation deny(Path path, String sid) {
            return new AclMutation(path, sid, true);
        }
    }
}
