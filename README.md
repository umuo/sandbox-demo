# Agent Sandbox Java SDK

这是一个 Maven 管理的 Java 8 SDK，用操作系统机制限制 Agent 启动的任意进程树。Agent 通过 `SandboxClient` Facade 调用，不需要接触 JNA、ACL、namespace 或 Seatbelt。它不解析 Bash、CMD、PowerShell、Python 或 Node 命令来判断“是否安全”。

`SandboxClient.capabilities().enforcement()` 会报告后端的执行完整性。Linux/macOS 在本文威胁模型内为 `FULL`；Windows 因 ACL 可表达性和 hard-link 限制为 `PARTIAL`，调用方无需从说明文字中自行推断。

当前默认后端：

| 平台 | 文件系统 | 网络 | 进程树 |
|---|---|---|---|
| Linux | bubblewrap mount/user/PID namespace；默认只暴露运行时与声明路径 | seccomp：DENY 拒绝网络 socket，ALLOW 仍拒绝宿主 Unix socket | bubblewrap PID 1 + `--die-with-parent` |
| macOS | Seatbelt deny-default profile；默认只读声明路径 | Seatbelt network policy | 策略由后代继承，Java 监督与超时回收 |
| Windows | 当前普通用户 + `WRITE_RESTRICTED` token + 随机 capability SID + NTFS ACL | 默认不限制网络 | kill-on-close Job Object + 进程/内存限制 |

Windows 实现是本项目的 Java/JNA 代码，不调用或分发 `codex.exe`、`codex-command-runner.exe` 或 `codex-windows-sandbox-setup.exe`。设计参考 Codex，但运行协议和生命周期由本项目维护。

## 安全模型

策略有两种读取模式：

- `ReadPolicy.DECLARED_ONLY`：Linux/macOS 默认值。只暴露系统运行时、`readableRoots` 和 `writableRoots`。
- `ReadPolicy.HOST`：允许广泛读取宿主文件，但写入仍限于 writable roots。

Windows 的 restricted-token 模型无法在保持通用 Win32 工具兼容性的同时构造可靠的文件系统读取 namespace，因此 Windows 默认使用 `ReadPolicy.HOST`。Windows 默认网络策略是 `NetworkPolicy.ALLOW`，SDK 不创建账户、不安装 worker、不修改 Firewall；请求 `DENY` 会失败关闭。Windows 上需要严格数据保密或网络隔离时，应使用 Hyper-V/独立 VM，或显式启用下文的专用账户后端。

共同约束包括：

- argv[0] 默认必须是绝对路径；PATH 搜索需要显式开启。
- 环境变量采用 allowlist，不继承 API Key 等宿主秘密。
- 所有策略路径在启动前 canonicalize，并拒绝策略路径中的符号链接；Windows 还拒绝 reparse point、UNC 和非 NTFS 路径，并持有防删除替换的句柄。
- 每次运行使用独立私有临时目录并在结束后清理。
- `readOnlyWorkingDirectory()` 可以表达真正的只读工作区；不再要求额外声明业务可写目录，SDK 私有临时目录仍可供工具缓存和临时文件使用。
- 支持最大 8 MiB 的一次性 stdin；写入后关闭，不开放交互式 TTY。
- stdout/stderr 有大小上限，timeout 有界，后台后代不会作为一次执行的遗留服务保留。
- 后端缺失或无法执行策略时失败关闭，不降级为普通 `ProcessBuilder`。

详细保证和限制见 [docs/SECURITY.md](docs/SECURITY.md)。

## 架构文档站点

`docs/` 提供 Windows、Linux/WSL2、macOS 的完整实现说明、策略模型、安全边界和术语表，使用 MkDocs Material 构建：

```bash
python3 -m venv .venv-docs
.venv-docs/bin/python -m pip install -r requirements-docs.txt
.venv-docs/bin/python -m mkdocs serve
```

严格构建：

```bash
.venv-docs/bin/python -m mkdocs build --strict
```

入口见 [docs/index.md](docs/index.md)，站点开发说明见 [docs/site-development.md](docs/site-development.md)。

## 构建

```bash
mvn clean verify
mvn package
```

`verify` 会执行测试、Maven/Java 版本门禁、Javadoc 和 google-java-format 检查。执行 `mvn install` 后，Agent 项目可以直接通过 Maven 引用：

```xml
<dependency>
    <groupId>io.github.sandboxdemo</groupId>
    <artifactId>agent-sandbox-sdk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

构建会生成：

```text
target/agent-sandbox-sdk-1.0.0-SNAPSHOT.jar          Maven SDK
target/agent-sandbox-sdk-1.0.0-SNAPSHOT-sources.jar 源码包
target/agent-sandbox-sdk-1.0.0-SNAPSHOT-javadoc.jar Javadoc
target/agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar      部署/演示 CLI
```

对外稳定入口是 `io.github.sandboxdemo.api` 和 `io.github.sandboxdemo.sdk`。`core`、`platform`、`demo` 包是内部实现，不应被 Agent 直接引用。完整集成指南见 [docs/SDK.md](docs/SDK.md)，制品发布要求见 [docs/PUBLISHING.md](docs/PUBLISHING.md)。

## Linux

安装 bubblewrap：

```bash
sudo apt-get install bubblewrap
```

默认寻找 `/usr/bin/bwrap` 或 `/bin/bwrap`。自定义路径必须是绝对路径：

```bash
export SANDBOX_BWRAP=/opt/sandbox/bin/bwrap
```

内核禁用 unprivileged user namespace 时后端会拒绝运行。网络 DENY 不要求创建 network namespace，而是由 seccomp 拒绝 `socket`、`socketpair` 和 `io_uring_setup`，避免某些 Ubuntu/AppArmor 环境在配置隔离 loopback 时出现 `RTM_NEWADDR`。生产镜像应固定 bubblewrap 版本并由包管理器或制品签名验证来源。

## macOS

使用系统 `/usr/bin/sandbox-exec` 和 Seatbelt。`sandbox-exec` 虽已 deprecated，但仍是当前可用的原生进程沙箱入口。执行用户程序前会用同一 profile 在私有临时目录运行可信探针；若宿主禁止嵌套 Seatbelt，或托管 runner 以 71/134 拒绝 profile，后端会返回 `SandboxBackendUnavailableException`。

真实集成测试需在非 App Sandbox 的原生 runner 上执行：

```bash
SANDBOX_RUN_PLATFORM_INTEGRATION=1 mvn test
```

## Windows：默认零配置

要求 Windows 10/11、NTFS、64 位 JDK 8+。普通用户进程直接调用即可，不需要管理员终端、setup、本地账户、`Log on locally` 权限或 Firewall 规则：

```java
SandboxClient client = SandboxClient.create();
SandboxResult result = client.execute(request);
```

默认后端拒绝在 elevated/Administrator 进程中运行；请以普通方式启动 Agent。它限制文件写入和进程树，但不限制网络，并且只能提供宽读取的 `ReadPolicy.HOST`。

## Windows：可选专用账户后端

只有明确需要 SDK 管理的 SID-scoped Firewall 网络阻断时，才从管理员终端安装原有专用账户后端：

```powershell
java -jar target\agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar setup-windows
```

setup 会：

1. 创建 `AgentSbxOffline`、`AgentSbxOnline` 两个非管理员本地账户；
2. 用当前真实用户的 DPAPI 加密随机账户密码；
3. 安装 offline 账户的入站/出站阻断规则，并确认所有 Firewall profile 已启用；
4. 把版本一致的 worker JAR 安装进受保护目录；
5. 给专用账户授予 Java runtime 的读取/执行权限，以及启动 worker 所需的 `Log on locally` 权限。

安装后必须显式选择该后端；`SandboxClient.create()` 仍保持零配置默认：

```java
SandboxClient client = SandboxClient.builder()
        .windowsProductionHome(SandboxRuntime.defaultWindowsHome())
        .build();
```

专用账户后端会根据 network policy 选择 online/offline 账户，启动安装的 worker，并使用双层 Job Object。升级应用后，先从新 fat JAR 重新运行 `setup-windows`，再启动新版服务；内部 worker 协议版本不匹配会失败关闭。

卸载需管理员终端：

```powershell
java -jar target\agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar uninstall-windows
```

卸载会移除 Firewall 规则、账本记录的 workspace/runtime ACE、专用账户和已知 runtime/session 文件，不会递归删除配置 home 中的未知调用方文件。

可用 `--home PATH` 修改默认 `%LOCALAPPDATA%\AgentSandboxSdk`。同一机器目前只支持一套固定名称的 sandbox 账户。

## Agent SDK

Linux/macOS 严格读取示例：

```java
Path workspace = Path.of("/srv/agent/workspace");

SandboxClient client = SandboxClient.create();
SandboxRuntimeStatus status = client.status();
if (!status.ready()) {
    throw new IllegalStateException(status.diagnostic());
}

SandboxRequest request = SandboxRequest.builder(workspace, "/bin/sh")
        .arguments("-c", "npm test")
        .readableRoot(Path.of("/srv/agent/input"))
        .writableRoot(workspace.resolve("generated"))
        .protect(workspace.resolve(".git"))
        .readPolicy(ReadPolicy.DECLARED_ONLY)
        .network(NetworkPolicy.DENY)
        .timeout(Duration.ofSeconds(60))
        .maxOutputBytes(4 * 1024 * 1024)
        .build();

SandboxResult result = client.execute(request);
```

Windows 零配置 workspace-write 示例：

```java
Path workspace = Path.of("D:\\agent-workspace");
String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");

SandboxRequest request = SandboxRequest.builder(
                workspace,
                Path.of(systemRoot, "System32", "cmd.exe").toString())
        .arguments("/d", "/s", "/c", "mvn test")
        .protect(workspace.resolve(".git"))
        .timeout(Duration.ofSeconds(60))
        .build();

SandboxResult result = SandboxClient.create().execute(request);
```

Windows Builder 默认即为 `ReadPolicy.HOST + NetworkPolicy.ALLOW`；这里无需配置账户、权限或 Firewall。

Windows 还可以把“新建/原地修改”与“删除/重命名”分开控制：

```java
SandboxRequest request = SandboxRequest.builder(project, powershell)
        .arguments("-NoProfile", "-NonInteractive", "-Command", modelCommand)
        .readOnlyWorkingDirectory()
        .writableRoot(project.resolve("generated").toRealPath())
        .readPolicy(ReadPolicy.HOST)
        .network(NetworkPolicy.ALLOW)
        .deletion(DeletionPolicy.DENY)
        .build();
```

该请求允许在 `generated` 中创建文件和原地改写内容，但拒绝删除与重命名。策略是一次
执行的不可变快照；规则变化时为下一次 `execute(...)` 构建新请求即可，不需要重建
`SandboxClient`。当前只有 Windows 后端支持 `DeletionPolicy.DENY`，其他后端会在执行前
失败关闭。

Windows 项目根只读、测试源码可写、AppData 可读、网络不受 SDK 限制的示例：

```java
Path project = Path.of("D:\\projects\\my-agent").toRealPath();
Path testSources = project.resolve("src\\test\\java").toRealPath();
Path appData = Path.of(System.getenv("USERPROFILE"), "AppData").toRealPath();
String powershell = Path.of(
        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
        "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();

SandboxRequest request = SandboxRequest.builder(project, powershell)
        .arguments("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", modelCommand)
        .readOnlyWorkingDirectory()
        .readableRoot(appData)
        .writableRoot(testSources)
        .readPolicy(ReadPolicy.HOST)
        .network(NetworkPolicy.ALLOW)
        .timeout(Duration.ofMinutes(2))
        .build();

SandboxResult result = SandboxClient.create().execute(request);
```

`readOnlyWorkingDirectory()` 会撤销 Builder 默认赋予项目根的写权限，随后仅给
`src\test\java` 增加写根。如果不再调用 `writableRoot(...)`，则整个项目保持只读，只有
SDK 管理的每次执行私有临时目录可写。上述目录必须在启动沙箱前由可信主进程创建。完整可运行类见
[`WindowsSelectiveWriteExample`](examples/sdk-consumer/src/main/java/example/WindowsSelectiveWriteExample.java)。

这里的“项目只读”精确指**禁止沙箱进程写项目根及其他子目录**；Windows 后端仍采用
`ReadPolicy.HOST`，不能承诺只有 project 与 AppData 可读。`NetworkPolicy.ALLOW` 表示 SDK
不添加网络阻断，宿主 Windows Firewall、企业策略和服务自身授权仍然生效。AppData
经常含有令牌、浏览器数据和应用配置，只应在业务确实需要时开放；若目标是严格读取
白名单，请把命令放进一次性 Hyper-V/VM，并只映射允许目录。

沙箱层接收任意 executable，不负责选择 Shell。生产 Agent 应在上层用 Shell Adapter 明确选择 `/bin/sh`、`cmd.exe`、Windows PowerShell 或 `pwsh.exe`。

## CLI 与 Demo

部署健康检查：

```bash
java -jar target/agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar status
```

```bash
java -jar target/agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar run \
  --cwd /absolute/project \
  --read-only-cwd \
  --readable /absolute/input \
  --read-policy declared-only \
  --network deny \
  --timeout 60000 \
  -- \
  /bin/sh -c 'find . -maxdepth 2 -type f | head'
```

`--read-only-cwd` 撤销 CLI 对 `--cwd` 的隐式写授权；可以不传 `--writable` 得到完整只读工作区，也可以继续用一个或多个 `--writable` 开放更窄的输出目录。

Windows 使用平台默认的宽读、网络不受限模式：

```powershell
java -jar target\agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar run `
  --cwd D:\project `
  --writable D:\project `
  --protect D:\project\.git `
  --deletion deny `
  -- `
  C:\Windows\System32\cmd.exe /d /s /c "mvn test"
```

运行本仓库的 Windows 选择性写入案例（只需先执行 `mvn install`）：

```powershell
mvn -f examples\sdk-consumer\pom.xml compile exec:java `
  -Dexec.mainClass=example.WindowsSelectiveWriteExample `
  -Dexec.args="$PWD"
```

案例会验证 `src\test\java` 写入成功、项目根写入被 Windows 拒绝，并读取 AppData
中的一个目录项。它只声明网络为 ALLOW，不主动访问公网。

运行 Windows 动态权限案例：

```powershell
mvn -f examples\sdk-consumer\pom.xml compile exec:java `
  -Dexec.mainClass=example.WindowsDynamicPolicyExample
```

案例先只开放 `output-a`，下一次执行切换为只开放 `output-b`，并验证宿主读取、新建、
原地修改成功，越界写入、删除和重命名权限被拒绝。完整代码见
[`WindowsDynamicPolicyExample`](examples/sdk-consumer/src/main/java/example/WindowsDynamicPolicyExample.java)。

演示 workspace 写入、外部写入阻断、protected path 阻断；Linux/macOS 还演示外部读取阻断：

```bash
java -jar target/agent-sandbox-sdk-1.0.0-SNAPSHOT-all.jar demo
```

## 上线前检查

- 在每个受支持的 OS 版本和文件系统上运行 `.github/workflows/ci.yml` 中的原生集成测试。
- 固定 JDK、JNA、bubblewrap 和应用制品版本，校验签名/hash。
- Windows 默认后端以普通非 elevated 用户运行；Linux/macOS 日常进程也不使用 root。
- 不向不可信命令传递 API Key、云凭据、Docker socket、SSH agent 或数据库密码。
- 对主动攻击型 native binary、恶意依赖安装和跨租户任务使用一次性 VM，而不是仅依赖进程级沙箱。
- 安排独立安全审计和故障注入；本仓库提供的是 production-hardened baseline，不是安全认证。

参考资料：[bubblewrap](https://github.com/containers/bubblewrap)、[Microsoft CreateRestrictedToken](https://learn.microsoft.com/windows/win32/api/securitybaseapi/nf-securitybaseapi-createrestrictedtoken)、[Windows Job Objects](https://learn.microsoft.com/windows/win32/procthread/job-objects)、[Codex Linux sandbox](https://github.com/openai/codex/tree/main/codex-rs/linux-sandbox)、[Codex Seatbelt policy](https://github.com/openai/codex/blob/main/codex-rs/core/src/seatbelt_base_policy.sbpl)。
