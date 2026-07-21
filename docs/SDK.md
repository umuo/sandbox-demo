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
- `SandboxRuntime`：Windows 安装/升级/卸载以及运行时健康检查。
- `SandboxCapabilities`：后端支持的读取和网络策略，用于 Agent 启动时协商能力。

`core`、`platform` 和 `demo` 包没有 SDK 兼容性承诺。

## Agent 服务封装

`SandboxClient` 可以作为单例使用；执行方法没有共享的可变策略状态，可以由多个 Agent 请求并发调用。Windows setup/uninstall 必须放在维护窗口，不能与执行并发。

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

        SandboxRequest request = SandboxRequest.builder(workspace, command)
                .protect(workspace.resolve(".git"))
                .readPolicy(reads)
                .network(NetworkPolicy.DENY)
                .timeout(Duration.ofMinutes(2))
                .maxOutputBytes(4 * 1024 * 1024)
                .environment(explicitEnvironment)
                .build();

        return client.execute(request);
    }
}
```

`status()` 是不修改持久配置的部署健康检查，也不会执行不可信命令。Windows 验证安装元数据、账户和 Firewall；Linux 实际运行一次可信 bubblewrap namespace 探针；macOS 实际运行一次可信 Seatbelt 探针。因此它能在启动阶段发现“文件存在但内核/AppArmor/外层 sandbox 拒绝使用”的环境。实际请求还会验证其动态路径/profile，调用方仍必须处理 `SandboxBackendUnavailableException`。

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
仅允许生成或修改测试代码，应先撤销这个默认写授权，再声明更窄的 writable root：

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
        .timeout(Duration.ofMinutes(2))
        .build();
```

权限效果：

- `project`：SDK 为专用账户授予读取/执行，写入由 write-restricted token 拒绝；
- `project\src\test\java`：读取和写入；
- `%USERPROFILE%\AppData`：为专用账户授予读取/执行；
- 网络：使用 online sandbox account，SDK 不安装本次执行的阻断策略；
- SDK 私有临时目录：内部读写，执行结束后尽力清理，不要求项目根可写。

所有声明路径必须事先存在，并且不得包含 reparse point。Windows 的 `HOST` 模式不是读取
白名单：普通 Windows ACL 允许的其他宿主文件仍可能可读。AppData 的读取 ACE 会记录在
SDK 安装账本中并持续到 `uninstall-windows`，因此不要把调用方任意路径直接作为
`readableRoot`，只允许服务端配置的可信目录。完整案例位于
`examples/sdk-consumer/src/main/java/example/WindowsSelectiveWriteExample.java`。

## 中断、超时与输出

- `SandboxClient.execute` 是同步调用。Agent 可以在自己的受控 Executor 中调用。
- 可通过 `standardInput(byte[])` 或 `standardInputUtf8(String)` 提供最多 8 MiB 的一次性 stdin；写入完成后 SDK 会关闭 stdin。本 SDK 不提供交互式 TTY。
- 中断等待线程会触发进程树回收，然后抛出 `InterruptedException`；调用方必须恢复或传播中断语义。
- 策略 timeout 到期返回 `SandboxResult.timedOut() == true`。
- stdout/stderr 分别受 `maxOutputBytes` 限制。必须检查 `stdoutTruncated()` 和 `stderrTruncated()`，不能把截断输出当作完整协议。
- 不要用无限线程池执行 Agent 命令；并发数、队列长度和每租户配额应由 Agent 服务控制。

## 环境变量

SDK 默认不会继承 Agent 的 API Key、云凭据或数据库密码。只有安全基础变量和 `SandboxRequest.environment` 中显式声明的值会传入。

以下目录变量由 SDK 管理，调用方不能覆盖：

```text
TEMP TMP TMPDIR HOME USERPROFILE XDG_CACHE_HOME
```

## Windows 生命周期

推荐安装器从管理员终端执行 CLI：

```powershell
java -jar agent-sandbox-sdk-1.0.0-all.jar setup-windows
```

也可以在独立的管理员安装进程中调用：

```java
SandboxRuntime.installWindows(SandboxRuntime.defaultWindowsHome());
```

SDK 作为普通 Maven 依赖时，setup 会自动定位并复制最小 worker classpath：SDK JAR、JNA 和 JNA Platform。Agent 不再需要把整个应用打包成一个 fat JAR。对于使用 Spring Boot nested JAR 等非文件 classpath 的部署，建议使用独立 CLI 完成 setup。

日常 Agent 进程不得使用管理员权限。启动时检查：

```java
SandboxRuntimeStatus status = SandboxRuntime.status();
```

升级 SDK 后必须在停止执行任务的维护窗口重新运行 setup，使已安装 worker 与 SDK 版本同步。

## 错误处理

- `SandboxBackendUnavailableException`：缺少 bubblewrap、Seatbelt 无法嵌套、Windows 未 setup 或策略不受支持。禁止降级成普通 `ProcessBuilder`。
- `SandboxException`：setup、ACL、进程创建、协议、输出读取等执行失败。
- `IllegalArgumentException`：请求本身不合法，例如保留环境变量、过量参数或无效策略。
- `InterruptedException`：上层取消或服务停止；调用方应继续传播中断。

## 发布兼容性

正式发布建议遵守语义化版本：

- patch：只修复实现，不改变 `api`/`sdk` 方法签名和策略语义；
- minor：向后兼容地增加能力；
- major：公共 API、默认安全策略或 Windows worker 协议不兼容。

升级时不能只替换 Windows runtime 中的某一个 JAR。始终从同一 SDK 版本重新执行 setup。
