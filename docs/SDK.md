# Agent SDK 集成指南

## Maven 坐标

在 SDK 仓库执行：

```bash
mvn clean install
```

Agent 项目添加：

```xml
<dependency>
    <groupId>io.github.sandboxdemo</groupId>
    <artifactId>agent-sandbox-sdk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

生产发布到 Nexus、Artifactory 或 Maven Central 时，应把版本改为不可变的正式版本，同时发布主 JAR、POM、sources 和 javadoc。Agent 不应使用 `-SNAPSHOT` 作为生产依赖。

## 公共 API

Agent 只应依赖：

```text
io.github.sandboxdemo.api
io.github.sandboxdemo.sdk
```

主要类型：

- `SandboxClient`：Facade，选择当前操作系统的 Strategy 并执行请求。
- `SandboxRequest`：一次执行的不可变请求；Builder 同时配置命令、策略和环境变量。
- `SandboxPolicy`：需要复用策略时可单独构建。
- `SandboxResult`：退出码、timeout、stdout/stderr、截断标志和执行时长。
- `SandboxRuntime`：运行时健康检查，以及可选 Windows 专用账户后端的安装/升级/卸载。
- `SandboxCapabilities`：后端支持的读取/网络策略和 enforcement 完整性，用于 Agent 启动时协商能力。

`core`、`platform` 和 `demo` 包没有 SDK 兼容性承诺。

## Agent 服务封装

`SandboxClient` 可以作为单例使用；执行方法没有共享的可变策略状态，可以由多个 Agent 请求并发调用。默认 Windows 后端不需要 setup。只有显式启用专用账户后端时，setup/uninstall 才必须放在维护窗口且不能与执行并发。

```java
import io.github.sandboxdemo.api.*;
import io.github.sandboxdemo.sdk.SandboxClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class AgentSandboxService {
    private final SandboxClient client = SandboxClient.create();

    public AgentSandboxService() {
        SandboxRuntimeStatus status = client.status();
        if (!status.ready()) {
            throw new IllegalStateException("Sandbox unavailable: " + status.diagnostic());
        }
    }

    public SandboxResult execute(
            Path workspace,
            CommandSpec command,
            Map<String, String> explicitEnvironment)
            throws SandboxException, InterruptedException {

        ReadPolicy reads = client.capabilities().supports(ReadPolicy.DECLARED_ONLY)
                ? ReadPolicy.DECLARED_ONLY
                : ReadPolicy.HOST;
        NetworkPolicy network = client.capabilities().supports(NetworkPolicy.DENY)
                ? NetworkPolicy.DENY
                : NetworkPolicy.ALLOW;

        SandboxRequest request = SandboxRequest.builder(workspace, command)
                .protect(workspace.resolve(".git"))
                .readPolicy(reads)
                .network(network)
                .timeout(Duration.ofMinutes(2))
                .maxOutputBytes(4 * 1024 * 1024)
                .environment(explicitEnvironment)
                .build();

        return client.execute(request);
    }
}
```

`status()` 是不修改持久配置的部署健康检查，也不会执行不可信命令。Windows 默认实际运行一次临时 restricted-token 探针；Linux 运行可信 bubblewrap namespace 探针；macOS 运行可信 Seatbelt 探针。显式专用账户后端则验证安装元数据、账户和 Firewall。因此它能在启动阶段发现“文件存在但内核或宿主策略拒绝使用”的环境。实际请求还会验证动态路径/profile，调用方仍必须处理 `SandboxBackendUnavailableException`。

`client.capabilities().enforcement()` 返回 `FULL` 或 `PARTIAL`。当前 Windows 原生后端返回 `PARTIAL`，因为宿主中向 Everyone 开放写权限的对象及 hard link 不能被 ACL 模型完整收敛；Linux 与 macOS 返回 `FULL`（均以安全文档中的威胁模型为限）。

不要把模型生成的整条字符串直接当作 executable。Agent 的 Shell Adapter 应明确构造 argv：

```java
CommandSpec command = CommandSpec.of(
        "/bin/sh", "-c", modelCommand);
```

Windows：

```java
CommandSpec command = CommandSpec.of(
        "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
        "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", modelCommand);
```

SDK 不会隐式插入 shell，也不会解析命令字符串进行安全判断。

## Windows 选择性写权限

Builder 默认把 working directory 同时作为 readable 和 writable root。若项目根必须只读，
可以只撤销默认写授权；若仅允许生成或修改测试代码，再声明更窄的 writable root：

```java
Path project = Path.of("D:\\projects\\my-agent").toRealPath();
Path testSources = project.resolve("src\\test\\java").toRealPath();
Path appData = Path.of(System.getenv("USERPROFILE"), "AppData").toRealPath();
Path powershell = Path.of(
        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
        "System32", "WindowsPowerShell", "v1.0", "powershell.exe");

SandboxRequest request = SandboxRequest.builder(project, powershell.toString())
        .arguments("-NoLogo", "-NoProfile", "-NonInteractive", "-Command", modelCommand)
        .readOnlyWorkingDirectory()
        .readableRoot(appData)
        .writableRoot(testSources)
        .readPolicy(ReadPolicy.HOST)
        .network(NetworkPolicy.ALLOW)
        .deletion(DeletionPolicy.DENY)
        .timeout(Duration.ofMinutes(2))
        .build();
```

默认零配置 Windows 后端的权限效果：

- `project`：当前普通用户读取，写入由 write-restricted token 拒绝；
- `project\src\test\java`：读取和写入；
- 删除/重命名：`DeletionPolicy.DENY` 使 writable root 内的操作被 NTFS ACL 拒绝；
- `%USERPROFILE%\AppData`：按当前用户原有 ACL 读取；
- 网络：SDK 不增加网络阻断；
- SDK 私有临时目录：内部读写，执行结束后尽力清理，不要求项目根可写。

`readOnlyWorkingDirectory()` 后不调用 `writableRoot(...)` 是合法策略，表示调用者声明的所有业务路径只读。私有临时目录仍会加入后端的内部读写根，但不会作为持久输出返回给调用者。

Fat JAR 的 `run` 命令使用 `--read-only-cwd` 表达同一语义；该选项可以与更窄的 `--writable PATH` 重复项组合。

所有声明路径必须事先存在，并且不得包含 reparse point。Windows 的 `HOST` 模式不是读取
白名单：当前用户 ACL 允许的其他宿主文件仍可能可读。完整案例位于
`examples/sdk-consumer/src/main/java/example/WindowsSelectiveWriteExample.java`。

如果运行期间会频繁调整规则，应把配置转换成每次执行的不可变策略快照。可以复用同一
个线程安全的 `SandboxClient`，但不要尝试修改已经运行中的请求：

```java
SandboxRequest.Builder next = SandboxRequest.builder(workspace, powershell)
        .arguments("-NoProfile", "-NonInteractive", "-Command", command)
        .readOnlyWorkingDirectory()
        .readPolicy(ReadPolicy.HOST)
        .network(NetworkPolicy.ALLOW)
        .deletion(DeletionPolicy.DENY);
for (Path root : currentWritableRoots) {
    next.writableRoot(root.toRealPath());
}
SandboxResult result = client.execute(next.build());
```

可运行的双请求切换示例位于
`examples/sdk-consumer/src/main/java/example/WindowsDynamicPolicyExample.java`。

## 中断、超时与实时流式输出

- `SandboxClient.execute` 是同步调用。Agent 可以在自己的受控 Executor 中调用。
- **实时流式控制台输出**：可在 `SandboxRequest.Builder` 中通过 `.stdoutConsumer(...)`、`.stderrConsumer(...)`、`.stdoutTextConsumer(...)`、`.stderrTextConsumer(...)` 或 `.outputListener(...)` 实时接收进程输出的二进制块或文本块（默认 UTF-8）。
- 文本回调默认使用 UTF-8。Windows 旧程序输出 GBK/CP936 等本地代码页时，可用 `.outputCharset(Charset.forName("GBK"))` 显式指定，或用 `.outputCharsetAuto()` 按 BOM、严格 UTF-8 校验和 Windows OEM code page 自动回退；也可传入 `.outputCharsetAuto(fallbackCharset)` 固定自动模式的回退编码。
- `SandboxResult.stdoutUtf8()`/`stderrUtf8()` 始终保持 UTF-8 语义；需要其他编码时使用 `stdoutText(charset)`/`stderrText(charset)`，需要自动检测时使用 `stdoutTextAuto()`/`stderrTextAuto()`。
- 可通过 `standardInput(byte[])` 或 `standardInputUtf8(String)` 提供最多 8 MiB 的一次性 stdin；写入完成后 SDK 会关闭 stdin。本 SDK 不提供交互式 TTY。
- 中断等待线程会触发进程树回收，然后抛出 `InterruptedException`；调用方必须恢复或传播中断语义。
- 策略 timeout 到期返回 `SandboxResult.timedOut() == true`。
- stdout/stderr 分别受 `maxOutputBytes` 限制。必须检查 `stdoutTruncated()` 和 `stderrTruncated()`，不能把截断输出当作完整协议。流式回调与同步 `SandboxResult` 并行工作，用户回调异常不会中断沙箱执行或进程树回收。
- 不要用无限线程池执行 Agent 命令；并发数、队列长度和每租户配额应由 Agent 服务控制。

## 环境变量

SDK 默认不会继承 Agent 的 API Key、云凭据或数据库密码。只有安全基础变量和 `SandboxRequest.environment` 中显式声明的值会传入。

以下目录变量由 SDK 管理，调用方不能覆盖：

```text
TEMP TMP TMPDIR HOME USERPROFILE XDG_CACHE_HOME
```

## Windows 默认生命周期

默认 Windows 后端不创建本地账户、不安装 worker、不修改 Firewall，也不要求管理员权限：

```java
SandboxClient client = SandboxClient.create();
SandboxRuntimeStatus status = client.status();
```

默认后端拒绝从 elevated 进程执行；应以普通用户启动 Agent。Windows Builder 自动采用 `ReadPolicy.HOST` 与 `NetworkPolicy.ALLOW`。

## 可选 Windows 专用账户后端

只有需要 SDK 管理的网络阻断时，才从管理员终端安装：

```powershell
java -jar agent-sandbox-sdk-1.0.0-all.jar setup-windows
```

也可以在独立的管理员安装进程中调用：

```java
SandboxRuntime.installWindows(SandboxRuntime.defaultWindowsHome());
```

SDK 作为普通 Maven 依赖时，setup 会自动定位并复制最小 worker classpath：SDK JAR、JNA 和 JNA Platform。Agent 不再需要把整个应用打包成一个 fat JAR。对于使用 Spring Boot nested JAR 等非文件 classpath 的部署，建议使用独立 CLI 完成 setup。

安装后显式选择，不改变 `SandboxClient.create()` 的零配置默认：

```java
SandboxClient client = SandboxClient.builder()
        .windowsProductionHome(SandboxRuntime.defaultWindowsHome())
        .build();
```

升级 SDK 后必须在停止执行任务的维护窗口重新运行 setup，使已安装 worker 与 SDK 版本同步。

## 错误处理

- `SandboxBackendUnavailableException`：缺少 bubblewrap、Seatbelt 无法嵌套、Windows elevated 运行、显式专用账户后端未 setup，或策略不受支持。禁止降级成普通 `ProcessBuilder`。
- `SandboxException`：setup、ACL、进程创建、协议、输出读取等执行失败。
- `IllegalArgumentException`：请求本身不合法，例如保留环境变量、过量参数或无效策略。
- `InterruptedException`：上层取消或服务停止；调用方应继续传播中断。

## 发布兼容性

正式发布建议遵守语义化版本：

- patch：只修复实现，不改变 `api`/`sdk` 方法签名和策略语义；
- minor：向后兼容地增加能力；
- major：公共 API、默认安全策略或 Windows worker 协议不兼容。

升级时不能只替换 Windows runtime 中的某一个 JAR。始终从同一 SDK 版本重新执行 setup。
