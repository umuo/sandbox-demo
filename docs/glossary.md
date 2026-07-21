# 术语表

本页解释文档和代码中出现的主要名字。每个词条优先回答三个问题：它是什么、解决什么、不能替代什么。

## 通用术语

### Agent

能够根据模型决策调用工具的主程序。本项目假设 Agent 主进程可信，但它传给沙箱的模型命令不可信。

### Sandbox

限制进程可访问资源的强制边界。它不是“命令看起来危险”的判断器，也不是恶意软件分类器。

### OS security boundary

由操作系统内核执行的权限边界。例如 Windows Access Check、Linux namespace/seccomp、macOS Seatbelt。用户态 Java 代码负责配置，最终允许/拒绝由内核决定。

### Policy

调用者声明的权限和资源约束集合，例如 readable roots、writable roots、network 与 timeout。

### Capability

持有某个不可伪造标识即获得特定能力的授权方式。本项目 Windows synthetic SID 是“写某个 root”的 capability；它不同于 Linux capability 位。

### Least privilege

最小权限原则：只赋予完成当前任务所需的最少路径、网络、环境变量、进程和时间权限。

### Fail closed

无法确认安全策略成功生效时拒绝执行。反义是 fail open，即出错后退化为无沙箱运行。

### TCB

Trusted Computing Base，可信计算基。所有能破坏安全保证的可信组件集合，包括内核、launcher、SDK 和运行时。

### Threat model

对攻击者能力、受保护资产、可信组件和非目标的明确描述。没有 threat model 的“安全”结论没有可验证含义。

### Integrity / Confidentiality / Availability

- Integrity：完整性，防止未授权修改；
- Confidentiality：机密性，防止未授权读取；
- Availability：可用性，防止资源被耗尽或服务被破坏。

当前 Windows 后端主要强化完整性，不是严格机密性边界。

### Host

运行 Java Agent 和沙箱进程的宿主操作系统。`ReadPolicy.HOST` 指允许宿主的广泛读取视图。

### Runtime roots

为了启动解释器、动态链接器和系统库必须读取的最小系统路径集合。它们不是业务数据目录。

### Workspace

一次 Agent 任务的项目或工作目录。应由可信服务创建，最好每任务独立。

### Readable root

允许读取的路径根。在 Windows 它只确保专用账户能读，不形成完整读取 allowlist。

### Writable root

允许读写的路径根。映射到 Windows capability ACL、Linux RW bind mount 或 macOS file-write allow rule。

### Protected path

位于 writable root 内但重新禁止写入的子路径，例如 `.git`。

### Canonicalization

把路径转换为真实、绝对、规范形式，以减少 `..`、相对路径和链接别名造成的策略歧义。

### TOCTOU

Time Of Check To Time Of Use，在校验资源与使用资源之间被替换的竞态。

### Process tree

根命令及其全部子孙进程。沙箱必须覆盖整个树，而不只是第一层 Shell。

### executable

操作系统真正启动的程序路径。与一整段 Shell command string 不同。

### argv

Argument Vector，传给 executable 的独立参数数组。保持 argv 分离可以减少宿主 Shell quoting 和注入问题。

### Shell Adapter

上层把模型命令转换为 PowerShell、CMD、sh 或 zsh argv 的适配器。它选择语法，不负责 OS 权限。

### Environment allowlist

只把明确批准的环境变量传给目标进程。反面是完整继承 `System.getenv()`。

## Java SDK 名称

### `SandboxClient`

Facade。Agent 端统一执行入口，并暴露 capabilities/status。

### `SandboxRunner`

Strategy 接口。平台实现负责把统一策略翻译成原生沙箱。

### `SandboxRequest`

一次执行的不可变请求：policy、command、environment 和 stdin。

### `SandboxPolicy`

不可变权限策略：路径、读取模式、网络、timeout、输出上限和 PATH 搜索选项。

### `CommandSpec`

executable + arguments 的值对象，不隐式插入 Shell。

### `SandboxResult`

包含 exitCode、timedOut、stdout/stderr、截断状态和 duration 的执行结果。

### `SandboxCapabilities`

当前后端支持的读取和网络策略。用于能力协商，不应只看 OS 名称猜测。

### `SandboxBackendUnavailableException`

平台能力缺失或策略无法安全执行，例如没有 bwrap、不能嵌套 Seatbelt、Windows 未 setup。

### `PathPolicyValidator`

解析、校验策略路径并创建私有临时目录的公共前置层。

### `EnvironmentPolicy`

构造最小子进程环境，阻止宿主秘密自动继承。

### `ProcessExecutor`

Unix-like 后端共用的有界进程执行、I/O 和 timeout 管理器。

## Windows 术语

### Win32

Windows 用户态系统 API 的统称。项目通过 JNA 调用 `Advapi32.dll`、`Kernel32.dll` 和 `User32.dll`。

### JNA

Java Native Access，让 Java 映射并调用 native API，而无需手写 JNI C/C++ bridge。JNA 只是调用桥，不是安全边界。

### Local user

本机账户。`AgentSbxOffline` / `AgentSbxOnline` 不属于管理员组，用于把沙箱命令与真实用户身份分离。

### DPAPI

Data Protection API。Windows 根据当前用户安全上下文加密 sandbox account 密码，避免配置文件直接保存明文。它不保护已经控制真实用户会话的攻击者。

### SID

Security Identifier，用户、组、会话或 synthetic principal 的安全标识。

### Synthetic SID

不对应登录账户、由程序生成的 SID。本项目用作短期 writable-root capability。

### Access Token

进程的 Windows 安全上下文，包括用户/组 SID 和 privilege。

### Primary Token

绑定到进程的主 token。目标进程由 restricted primary token 创建。

### Restricted Token

通过 `CreateRestrictedToken` 得到的低权限 token，可删除 privilege、禁用 SID 或加入 restricting SID。

### Restricting SID

restricted token 中触发第二次访问检查的 SID。正常 SID 检查和 restricting SID 检查都允许才授权。

### `WRITE_RESTRICTED`

只在写访问时考虑 restricting SID 的 token 标志。它带来兼容的宽读限写模型，但不能构造严格读取白名单。

### `DISABLE_MAX_PRIVILEGE`

移除新 token 中大多数 privilege 的标志。

### `LUA_TOKEN`

产生 Limited User Account 风格 token 的标志。

### Security Descriptor

Windows securable object 的安全元数据，包括 owner、DACL、SACL 等。

### ACL / DACL / ACE

- ACL：访问控制列表；
- DACL：决定允许/拒绝访问的列表；
- ACE：DACL 中的一条主体+权限记录。

### NTFS

Windows New Technology File System。当前后端要求策略路径位于 NTFS，以依赖其 ACL 与文件句柄语义。

### `icacls.exe`

Windows 系统 ACL 管理命令。项目用独立 argv 调用它配置 SID ACE，不让模型命令参与。

### OI / CI / RX / M

- OI：Object Inherit；
- CI：Container Inherit；
- RX：Read + Execute；
- M：Modify。

### Reparse point

Windows 路径重解析机制的统称，junction/symlink 等可基于它实现。策略路径中拒绝。

### UNC path

例如 `\\server\share` 的网络路径。当前后端拒绝，因为本地 NTFS ACL 假设不能安全映射到远端语义。

### Path Lease

执行期间持有路径句柄且不允许 delete sharing，降低校验后路径被替换的风险。

### `CreateProcessWithLogonW`

用专用本地账户凭据启动可信 worker 的 Win32 API。

### `CreateProcessAsUserW`

用 restricted token 启动真正目标进程的 Win32 API。

### Suspended process

线程尚未执行用户代码的进程。目标在加入 Job Object 后才恢复，减少边界外抢跑。

### Private Desktop

独立 Win32 desktop 对象，隔离窗口和消息交互。它不限制文件、网络或内存。

### Job Object

管理进程集合的 Windows 内核对象，可实现 kill-on-close、进程数和 Job 内存限制。

### Handle inheritance allowlist

只允许 stdin/stdout/stderr 句柄进入目标进程，阻止其他 launcher 句柄泄漏。

### Windows Firewall LocalUser rule

按账户 SID 匹配流量的 Firewall 规则。offline 账户拥有入站/出站 block rule。

### Online / Offline account

- Online：SDK 不添加网络阻断；
- Offline：由 SID-scoped Firewall rule 阻断网络。

## Linux 术语

### bubblewrap / bwrap

非特权 sandbox 构造工具，组合 namespace、bind mount、capability drop 和 seccomp 后 exec 目标命令。

### Namespace

Linux 内核资源视图隔离机制。user、mount、PID、IPC、UTS、network namespace 各自隔离不同资源。

### User namespace

隔离 UID/GID 映射和 namespace 内 capability。

### Mount namespace

隔离挂载表，用于构造只含 runtime 和声明路径的文件系统视图。

### PID namespace

隔离进程编号并提供沙箱 PID 1。

### IPC namespace

隔离 System V IPC、POSIX message queue 等 IPC 对象。

### UTS namespace

隔离 hostname/domainname。

### Network namespace

隔离接口、路由、端口和 socket 网络栈视图。

### Bind mount

把宿主已有路径映射到 mount namespace；`--bind` 可写，`--ro-bind` 只读。

### procfs

通常挂载在 `/proc` 的内核进程/系统信息文件系统。在新 PID namespace 中需重新挂载。

### `/dev`

设备文件树。bubblewrap 提供受控 dev 视图，而不是暴露宿主全部设备。

### Linux capability

把 root privilege 拆分成多个能力位。与本项目 Windows Capability SID 只是同名概念，不是同一机制。

### seccomp

Secure Computing，内核系统调用过滤机制。

### classic-BPF / cBPF

seccomp filter 使用的经典 Berkeley Packet Filter 指令格式。这里过滤 syscall 元数据，不是在过滤网络数据包。

### Audit architecture

seccomp_data 中标识 syscall ABI/架构的值。过滤器先验证它，避免 syscall number 在不同架构上含义不同。

### syscall

用户进程请求内核服务的入口，例如 `socket`、`openat`、`execve`。

### `AF_UNIX`

Unix-domain socket 地址族，用于 Docker、D-Bus、SSH agent 等本机 IPC。当前过滤器返回 EPERM。

### `EPERM`

POSIX “Operation not permitted” 错误。被 seccomp 拒绝的 AF_UNIX socket 调用收到该错误。

### `no_new_privs`

保证后续 exec 不获得新 privilege 的进程标志，也是非特权安装 seccomp filter 的前提之一。

### `--die-with-parent`

bubblewrap 父 launcher 消失时结束沙箱的生命周期选项。

### WSL2

Windows Subsystem for Linux 2，使用真实 Linux 内核，可在满足 user namespace 等条件时使用 Linux 后端。

## macOS 术语

### Seatbelt

macOS 内核沙箱机制，根据 profile 限制文件、网络、Mach、sysctl、IOKit 等操作。

### `sandbox-exec`

把 SBPL profile 应用到命令的系统 launcher。接口已 deprecated，需要持续实机验证。

### SBPL

Sandbox Profile Language，Seatbelt 的 Lisp 风格策略语言。

### `deny default`

没有明确 allow rule 的操作全部拒绝。

### `file-read*` / `file-write*`

Seatbelt 文件读取/写入操作族。

### `subpath` / `literal`

SBPL 路径过滤器：subpath 匹配整个子树，literal 只匹配单一路径。

### `param` / `-D`

通过 launcher 参数向 profile 安全传路径值，避免把用户路径拼成 SBPL 代码。

### Mach port / Mach lookup

macOS 服务发现和 IPC 机制。profile 只允许明确的基础系统服务名。

### sysctl

查询或设置内核参数的接口。当前只开放 CPU、内存、OS 版本等最小读取 allowlist。

### IOKit

macOS 访问设备和驱动 user client 的框架。当前只开放一个有限 registry entry class。

### pseudo-TTY / pty

伪终端设备。为需要终端语义的工具提供受限支持，不等于 SDK 已提供完整交互式终端。

### Nested sandbox

已受 App Sandbox/Seatbelt 限制的宿主再次应用 profile。系统可能拒绝，SDK 会失败关闭。
