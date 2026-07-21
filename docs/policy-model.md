# 策略模型

## `SandboxRequest`

`SandboxRequest` 表示一次不可变执行。理解它最简单的方式是把字段分成四组：命令、文件、网络和资源。

```java
SandboxRequest request = SandboxRequest.builder(workspace, executable)
        .arguments(arguments)
        .readableRoot(input)
        .writableRoot(output)
        .protect(workspace.resolve(".git"))
        .readPolicy(ReadPolicy.DECLARED_ONLY)
        .network(NetworkPolicy.DENY)
        .timeout(Duration.ofSeconds(60))
        .maxOutputBytes(4 * 1024 * 1024)
        .environment("BUILD_MODE", "test")
        .standardInputUtf8("input")
        .build();
```

## 命令字段

### executable

真正由操作系统启动的程序，例如 `/bin/sh` 或 `powershell.exe`。生产调用默认要求绝对路径，防止工作目录或 PATH 中的同名恶意程序被选中。

### arguments / argv

传给 executable 的独立参数数组。SDK 不把整个命令重新拼成宿主 Shell 字符串；只有调用者明确选择 Shell 时，Shell 才解释 `-c` 或 `-Command` 后的内容。

### standardInput

最多 8 MiB 的一次性输入。SDK 写入后关闭 stdin，不提供交互式 TTY。

## 文件字段

### workingDirectory / cwd

目标进程的当前工作目录。Builder 默认同时把它加入 readable roots 和 writable roots，以提供常见的 workspace-write 行为。

如果项目根必须只读，使用：

```java
SandboxRequest.builder(project, executable)
        .readOnlyWorkingDirectory()
        .writableRoot(project.resolve("src/test/java"))
        // ...
        .build();
```

`readOnlyWorkingDirectory()` 只撤销 Builder 自动添加的“项目根可写”，不会移除项目根的读取声明。

### readableRoots

调用者声明可以读取的目录。含义取决于平台和 `ReadPolicy`：

- Linux/macOS `DECLARED_ONLY`：只有系统最小运行时和声明路径可读；
- `HOST`：宿主可见文件广泛可读；
- Windows：当前只支持 `HOST`，readable roots 用于给专用账户补充 RX ACL，不构成完整读取白名单。

### writableRoots

允许创建、修改和删除文件的根目录。每个 writable root 也自动成为 readable root。

策略至少需要一个 writable root。SDK 私有临时目录会在校验后作为内部 writable root 加入，但它不替代调用者明确的数据输出路径。

### protectedPaths

位于 writable root 内、但必须重新禁止写入的路径，例如 `.git`、`.agent` 或 `secrets`。

```text
workspace                 RW
workspace/src             RW
workspace/.git            R
```

Linux 用只读 bind mount 覆盖，macOS 发出 `deny file-write*`，Windows 给 capability SID 增加 deny write/delete ACE。

### 路径优先级

逻辑优先级是：

```text
基础可读视图
  → writable roots 覆盖为 RW
  → protected paths 覆盖回 RO
```

声明前必须由可信主进程创建目录。不要让模型决定任意 `readableRoot` 或 `writableRoot`。

## 读取策略

### `ReadPolicy.DECLARED_ONLY`

目标是最小可见性：系统运行时 + readable roots + writable roots。Linux 和 macOS 支持；Windows 生产后端会失败关闭。

### `ReadPolicy.HOST`

允许广泛读取宿主文件，写入仍由 writable roots 限制。适用于兼容性优先、重点防误写的开发 Agent。

!!! danger "HOST 不是保密边界"
    `HOST` 不能阻止命令读取 SSH key、浏览器数据或其他由当前安全身份可读的文件。即使网络关闭，秘密仍可能出现在 stdout 或之后写入 workspace。

## 网络策略

### `NetworkPolicy.DENY`

- Windows：使用有入站/出站 SID-scoped block rules 的 offline account；
- Linux：额外创建 network namespace，且 seccomp 始终阻止 AF_UNIX socket；
- macOS：不向 Seatbelt profile 添加 network allow rule。

### `NetworkPolicy.ALLOW`

表示 SDK 不阻止常规 IP 网络：

- Windows 选择 online account；
- Linux 不执行 `--unshare-net`，但仍阻止宿主 Unix-domain socket；
- macOS 允许 inbound、outbound 和 system socket。

ALLOW 不会绕过宿主 Firewall、企业策略、路由、TLS 或应用认证。

## 环境变量

调用者通过 `environment(name, value)` 显式传值。SDK 会拒绝调用者覆盖这些保留变量：

```text
TEMP TMP TMPDIR HOME USERPROFILE XDG_CACHE_HOME
```

不要把 `System.getenv()` 整体传入请求。

## 资源与输出

### timeout

一次执行允许的最长时间，必须为正数且不超过 24 小时。超时后后端终止受监督进程树并返回 `timedOut=true`。

### maxOutputBytes

stdout 和 stderr 各自的最大捕获大小，默认 4 MiB，API 上限 64 MiB。超过后继续排空管道以避免死锁，但只保留上限内的数据，并设置截断标志。

### allowPathSearch

默认 `false`。打开后 launcher 可以通过 PATH 查找 argv[0]，会扩大 PATH 劫持面。生产 Agent 应优先解析并配置可信 executable 的绝对路径。

## 平台能力矩阵

| 能力 | Windows | Linux | macOS |
|---|---:|---:|---:|
| `DECLARED_ONLY` | 不支持 | 支持 | 支持 |
| `HOST` | 支持 | 支持 | 支持 |
| 多 writable roots | 支持 | 支持 | 支持 |
| protected paths | 支持 | 支持 | 支持 |
| network deny | Firewall | namespace + seccomp | Seatbelt |
| network allow | online account | IP 网络；Unix socket 仍阻止 | Seatbelt allow |
| 强进程树容器 | Job Object | PID namespace | 无等价 Job Object |

调用前可以通过 `SandboxClient.capabilities()` 协商能力，不要根据操作系统名称猜测。

## Windows 选择性写入示例

```java
Path project = Path.of("D:\\projects\\my-agent").toRealPath();
Path tests = project.resolve("src/test/java").toRealPath();
Path appData = Path.of(System.getenv("USERPROFILE"), "AppData").toRealPath();

SandboxRequest request = SandboxRequest.builder(project, powershell)
        .arguments("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", command)
        .readOnlyWorkingDirectory()
        .readableRoot(appData)
        .writableRoot(tests)
        .readPolicy(ReadPolicy.HOST)
        .network(NetworkPolicy.ALLOW)
        .timeout(Duration.ofMinutes(2))
        .build();
```

这个请求准确保证的是“项目根不可写、`src/test/java` 可写”。它不能保证 Windows 上只有 project 和 AppData 可读。
