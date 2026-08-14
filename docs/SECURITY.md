# Security model

## Scope

This project is a process-level sandbox for a single-host Java Agent. The attacker may control the executable, argv, scripts, subprocesses and file names inside declared writable roots. The host kernel, administrator/root account, sandbox launcher JAR and configured runtime are trusted.

Command parsing is not a security boundary. Every child is launched under an OS-enforced policy.

## Enforced properties

### Common

- Missing prerequisites, unsupported policy combinations and setup/version mismatches fail closed.
- The launcher clears the inherited environment and adds only a small allowlist plus caller-explicit values.
- `SandboxClient` rejects read, network or deletion policies absent from the selected backend's advertised capabilities before invoking it. Request diagnostics expose environment names and stdin length, not environment values or stdin contents.
- Bare executable lookup is disabled by default. Absolute executable paths are canonicalized before launch.
- Policy paths must exist, are canonicalized immediately before setup and cannot contain symbolic-link components. Every run gets an owner-private temporary directory.
- Output is bounded to 64 MiB per stream at the API level and 4 MiB by default.
- A timeout terminates the supervised process tree. Normal completion also reaps observed background descendants.

### Linux

- bubblewrap starts with an empty tmpfs root for `DECLARED_ONLY`, then read-only binds runtime/readable roots, writable binds writable roots and finally read-only binds protected paths.
- `HOST` uses a read-only bind of `/` before writable overlays.
- User, PID, IPC and UTS namespaces are unshared. Network enforcement does not depend on creating a network namespace.
- All Linux capabilities are dropped. A seccomp classic-BPF filter validates the audit architecture. DENY rejects `socket`, `socketpair`, and `io_uring_setup`; ALLOW still rejects AF_UNIX sockets and `io_uring_setup`, preventing access to mounted Docker, D-Bus, or SSH-agent sockets even when host reads are enabled.
- bubblewrap supplies PID 1/reaping and `--die-with-parent` behavior.

### macOS

- Seatbelt starts from `(deny default)` and grants process execution, a minimized runtime service/sysctl allowlist, declared file reads and declared file writes.
- Protected path deny rules are emitted after writable grants.
- Network rules are absent for DENY and explicitly granted only for ALLOW.
- Paths are passed through `sandbox-exec -D` parameters instead of interpolated into SBPL text.

### Windows setup-free default backend

- `SandboxClient.create()` runs commands from the current unelevated user token; it creates no accounts, installs no worker and changes no Firewall state.
- Windows defaults resolve to `ReadPolicy.HOST` and `NetworkPolicy.ALLOW`. The default backend advertises only ALLOW and fails closed if DENY is requested.
- Each writable root receives a short-lived random capability-SID write ACE. With `DeletionPolicy.DENY`, the ACE omits `DELETE` and an inherited deny ACE covers `DELETE | FILE_DELETE_CHILD`. The target token uses `DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED` and deliberately omits the real user SID from its restricting list.
- The target starts suspended with an explicit executable, sanitized environment, inherited-handle allowlist and private desktop, then joins a kill-on-close Job with active-process and memory bounds before resume.
- UNC, non-NTFS and reparse-point paths are rejected; important paths remain open without delete sharing through execution.

### Optional Windows dedicated-account backend

- setup and run are separated. Only setup/uninstall requires elevation; the Agent and per-command runner remain unprivileged.
- Commands run as a dedicated online/offline local user. Account passwords are random and stored with user-scoped DPAPI; account SID identity is revalidated before every run.
- The offline account has persistent inbound and outbound Windows Firewall block rules scoped by SID. Firewall profiles, rule action/direction and SID scope are checked before a denied-network run.
- Writable roots receive a short-lived random synthetic SID write ACE. That SID is placed in a token created with `DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED`. `DeletionPolicy.DENY` additionally places a delete/delete-child deny ACE on each writable root while retaining create and in-place write access. The production restricting list also contains the dedicated account SID, Everyone (`S-1-1-0`), the current logon-session SID and Windows' `S-1-5-33` Write Restricted Code compatibility SID. The development backend deliberately omits the real host-user SID, because including it would collapse its write boundary. The worker is started with `LOGON_WITH_PROFILE`; the target receives an allowlisted environment derived from that dedicated account via `CreateEnvironmentBlock`, with sandbox-controlled directories and explicit request values overlaid. This supports Win32/.NET initialization without inheriting the Agent process's secret environment. Compatibility SIDs grant nothing unless the normal account access check also passes and the object's DACL has a matching ACE. Consequently, the dedicated sandbox profile and a host location writable by Everyone remain writable; production hosts must not store trusted data in the sandbox accounts' profiles or expose security-sensitive world-writable locations. `LUA_TOKEN` is deliberately not used because it is UAC filtering rather than the capability-SID boundary. Protected roots receive a write/delete deny ACE for the current capability SID.
- Random capability ACEs are removed after each command. Dedicated-user root ACEs are recorded in a locked, fsynced ledger and removed at uninstall.
- Declared readable-root ACEs for the dedicated accounts are persistent until uninstall; callers must select readable roots from a trusted service-side allowlist rather than pass arbitrary model input.
- UNC, non-NTFS and reparse-point policy paths are rejected. Important path objects are opened without delete sharing and retained through execution.
- The worker verifies its actual account SID and consumes a sealed, bounded, versioned request file from a protected session directory. Sealing denies only concrete mutation rights (not the overlapping generic read/control bits) and retains a host read handle without write/delete sharing.
- The target is created suspended with an explicit application path, environment block and inherited-handle allowlist. It receives a private desktop before resume; the current window station and private desktop both receive short-lived per-command capability ACEs.
- The worker and target each have a kill-on-close Job Object. The target Job also limits active processes (default 64) and aggregate Job memory (default 2048 MiB).

## Deliberate limitations

### Windows reads

Windows `WRITE_RESTRICTED` restricts write access, not reads. Dedicated accounts reduce access to private user data but ordinary `Users`/`Authenticated Users` ACEs can still expose host files. Applying restricting SIDs to all object access breaks many general Win32 toolchains because files, registry objects, services and IPC endpoints do not share one practical allowlist namespace.

For that reason Windows accepts only explicit `ReadPolicy.HOST`; `DECLARED_ONLY` fails closed. Do not describe the native Windows backend as a confidentiality boundary. Use Hyper-V or another dedicated VM with explicit mounts for strict read isolation.

The public capability reports `SandboxEnforcement.PARTIAL` on Windows so callers do not have to infer these ACL and hard-link gaps from prose. Linux and macOS report `FULL` within this document's threat model.

### Kernel and privileged attacks

No process sandbox protects against kernel vulnerabilities, malicious drivers, root/Administrator code, firmware attacks or a compromised launcher/runtime. Linux user namespaces, macOS Seatbelt and Windows tokens all share the host kernel.

### TOCTOU and filesystem aliases

Canonicalization and symlink/reparse rejection reduce common path substitution attacks. Windows additionally retains handles. Linux/macOS bind/profile setup still has a validation-to-launch window if an attacker outside the sandbox can rename ancestor directories. Workspace parents must be controlled by the trusted service account.

Hard links inside a writable root can reference files on the same filesystem. The service must not allow an attacker to prepopulate the workspace with hard links to sensitive host files. Prefer a newly created per-task workspace on a dedicated volume and validate uploaded archives without preserving links.

### Process lifetime

Windows Job Objects and Linux PID namespaces are the primary strong tree boundaries. macOS has no Job Object equivalent here; Seatbelt is inherited, and Java tracks/reaps observed descendants. A deliberately racing daemonization sequence deserves separate adversarial testing for every supported macOS release. Use a VM when process-lifetime escape is unacceptable.

### Network details

- Linux DENY blocks creation of socket endpoints with seccomp, including loopback and Unix-domain sockets. It relies on the trusted Java launcher not leaking pre-opened network descriptors.
- macOS DENY is a Seatbelt network policy.
- The default Windows backend does not restrict network access. Windows DENY is available only through the explicitly installed dedicated-account backend and depends on Windows Defender Firewall state and policy.
- Network ALLOW never grants access to secrets that are not otherwise reachable, but SSRF and access to local TCP services remain application risks.
- Network ALLOW means this SDK adds no deny rule for the execution. Host Firewall, enterprise policy, routing and application authentication still apply; it is not a firewall bypass.

### Availability

Resource exhaustion is only partially controlled. Windows has process and memory limits. Linux/macOS currently rely on timeout and host service limits; production deployments should add cgroup v2/systemd limits on Linux and launchd/service-level limits on macOS. Disk quotas are outside this project.

## Operational requirements

1. Use one freshly created workspace per task/tenant; the workspace parent is trusted and not writable by the sandbox user before policy setup.
2. Keep runtime, launcher JAR, setup home and executable paths outside writable roots.
3. Pin and verify dependencies and native tooling. Do not accept a caller-controlled `SANDBOX_BWRAP`, PATH, Windows home or resource-limit environment.
4. When the optional dedicated-account backend is used, run setup/upgrade in a maintenance window and re-run setup for every deployed SDK version.
5. Monitor timeouts and output truncation; also monitor Firewall, setup, worker protocol and session cleanup when the optional backend is enabled.
6. Run native integration tests on the exact OS image before promotion. macOS tests require `SANDBOX_RUN_PLATFORM_INTEGRATION=1`; optional Windows dedicated-account tests require installed setup and `SANDBOX_WINDOWS_PRODUCTION_TEST=1`.
7. Perform an independent security review before treating the implementation as a production control.

## VM boundary recommendation

Use a disposable, hardware-virtualized worker for downloaded native executables, malicious package-install scenarios, multi-tenant hostile workloads, strict Windows read isolation, or any workload where a host compromise has high impact. Expose only a dedicated input/workspace/output exchange; never map a user home, credential directory, Docker socket or entire drive.
