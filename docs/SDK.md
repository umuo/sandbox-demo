# Agent SDK 集成指南

## Maven 坐标

在 SDK 仓库执行：

```bash
mvn clean install
```

Agent 项目添加：

```xml
<dependency>
    <groupId>io.github.umuo</groupId>
    <artifactId>agent-sandbox-sdk</artifactId>
    <version>0.1.0-alpha.1</version>
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

## 输出额度与超限终止

现有默认保持 `onOutputLimit(TRUNCATE)`：stdout/stderr 分别保留最多 `maxOutputBytes` 字节，继续排空目标输出。`successful()` 仍仅表示未超时且退出码为零。新 `SandboxResult.completeSuccessful()` 还拒绝截断和输出超限终止；`SandboxCompletion.completeSuccessful()` 在此基础上要求回收已确认。

```java
SandboxRequest request = SandboxRequest.builder(workspace, "/bin/sh")
        .arguments("-c", "printf synthetic-output")
        .maxOutputBytes(1024 * 1024) // 每个流；既有默认 4 MiB，最大 64 MiB
        .maxCombinedOutputBytes(1024 * 1024) // 可选共享额度；最大 128 MiB
        .onOutputLimit(OutputLimitAction.TERMINATE)
        .outputCallbackMode(OutputCallbackMode.RETAINED)
        .build();
```

聚合额度默认关闭（getter 为 0）。配置后，两个捕获线程原子共享额度，保留总字节数不会超过配置值；流之间的额度分配由读取到达顺序决定。恰好达到额度不算超限，观察到额外字节时触发。TERMINATE 请求停止目标，执行句柄以 `OUTPUT_LIMIT` 及同名错误码返回；同步结果可检查 `outputLimitTerminated()`。输出停止、超时与取消以首先记录的停止原因作为契约，清理仍受既有宽限约束。回调或宿主清理卡住时，交付可以不含结果，但原因稳定，独立资源快照保留 UNKNOWN/FAILED。

回调默认 `RAW`，兼容原文观察语义：TRUNCATE 下可以收到超过保留额度的原文，TERMINATE 下也可能收到触发超限的整块原文。`RETAINED` 将回调限制在各流实际保留的前缀，共享额度同样约束字节回调；文本回调再对这些字节进行解码。超限信号在回调之前记录，因此阻塞回调不会使期限交付等待无界。自动字符集探测使用有限采样；合法 UTF-8 前缀持续以半个字符结束时也不会无限缓存到 EOF。

这两种回调都是观察接口，不能作为受治理的最终内容交付。受控调用方应不注册回调，等待完整成功及回收确认，再严格解码并治理最终字节；失败结果中的字节仅供显式诊断，不能把截断前缀交付为完整答案。输出额度不是 CPU、内存、PID 或磁盘硬限额。

内建 Linux/macOS 和两个 Windows 后端支持这些选项。旧自定义 runner 通过新增默认方法 `supportsOutputPolicy` 只接受旧默认值；新配置在目标启动前拒绝。扩展后端必须显式声明并实现对应保证。Windows 内部 worker 文件协议升级为 v6，携带输出策略和终止标记；已安装的专用账户 worker 必须用同版本 SDK 重新供应运行时，旧协议明确拒绝，不能静默忽略新配置。消费示例见 `examples/sdk-consumer/src/main/java/example/SandboxOutputLimitExample.java`。

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

## AGT 接入维护中的新增契约

以下 API 属于当前开发源码，尚未发布到 `0.1.0-alpha.1`，消费时须使用本地构建的制品。

### 实际策略预检

在授权、参数改写之后，预算提交之前调用：

```java
SandboxRequest request = SandboxRequest.of(policy, command);
client.validate(request);
// 宿主在此提交执行预算。
SandboxResult result = client.execute(request);
```

`validate(request)` 现在委托 `readiness(request)`；失败时抛出其中的结构化异常。四个内建后端均实现实际策略探针；旧自定义 runner 未实现时返回 `POLICY_UNSUPPORTED / PROBE`。静态能力不满足的请求在探针启动前拒绝。

```java
SandboxPolicyReadiness check = client.readiness(request);
System.out.println(check); // 状态、错误码、耗时与回收证据，不含请求正文。
if (!check.ready()) {
    throw check.error().get();
}
// 版本信息需显式读取，缺失表示未知，不能猜测版本。
String version = check.backendInfo().backendVersion().orElse("UNKNOWN");
```

`capabilities()` 描述机制；`status()` 检查部署环境；`readiness(request)` 检查本次路径和策略；`cleanup()` 独立描述探针资源回收。`ready()` 不要求所有资源都已确认：macOS processes 和 Windows hostResources 仍可能 UNKNOWN。宿主要求全面回收时必须另行拒绝 UNKNOWN/FAILED。

Linux 探针实际进入 bubblewrap namespace、mount 和 seccomp 策略；macOS 使用请求的 Seatbelt profile；Windows 使用同一 token/Job 或专用账户 worker/ACL 准备路径。Windows 默认仍拒绝严格读取和网络拒绝，不会自动改成 HOST/ALLOW。探针仅运行 SDK 固定可信程序，不执行请求 argv/stdin，不调用监听器。探针使用独立受控环境，不接受调用方 PATH、shell 启动脚本或加载器注入变量；这只约束探针；目标进程的严格环境需显式使用 SDK-06 配置。

探针会准备临时目录；Windows 可能暂时调整 ACL、创建会话资源。它验证可执行路径的可见性，但不证明目标的全部动态依赖可用，也不消除预检与执行之间的竞态。执行仍重新实施策略；预检结果不能作为可缓存授权令牌。程序必须位于平台运行时根或宿主声明的可读根，JDK 安装目录需显式声明。

`SandboxBackendInfo` 提供后端、OS、架构、JDK、SDK 版本。Seatbelt 以系统版本标识；Windows 标明 JNA/worker 协议；Linux 优先从同一隔离探针读取 bwrap 版本，无法取得则为空。源码测试没有制品 manifest 时 SDK 版本为空。默认字符串不输出原始版本字段，显式 getter 供宿主选择审计字段。

预检使用同一绝对期限和清理宽限，但此入口是同步调用，不能强行中断卡住的宿主文件系统、原生准备或自定义后端。宿主应在有限工作池中调度，不能把请求 timeout 当作所有宿主操作的硬时限。消费示例见 `examples/sdk-consumer/src/main/java/example/SandboxReadinessExample.java`。

### 错误分类与诊断

使用 `SandboxException.code()` 和 `phase()` 映射错误，不匹配自由文本。内建路径准备、平台部署、启动、捕获、探针与已确认清理失败均按操作分类；输出超限、超时和取消保留独立停止原因。旧构造器和无法分类的自定义执行错误保守返回 `UNKNOWN`。客户端也封装后端未处理的 RuntimeException/LinkageError，原始异常只在显式调试接口中提供。

Windows worker 协议 v6 按枚举名称传递错误码和阶段，原始诊断限长并只通过调试 API 读取；旧版本或无效枚举明确拒绝。专用账户安装必须重新供应同版本 worker。协议不是不可信客户端的网络接口。

旧构造器和方法签名保留，但诊断行为有明确变化：

- `getMessage()`、`toString()` 和普通堆栈打印只显示错误码与阶段，不显示原始诊断或 cause；`getCause()` 默认返回 null。
- 原始内容改为显式 `debugMessage()`、`debugCause()`，仅可用于受控调试，不能进入默认审计或产品响应。
- `SandboxResult.toString()` 仅显示输出字节数、状态与耗时；读取正文仍需显式调用 `stdout()` / `stderr()` 等接口。
- `SandboxRuntimeStatus.toString()` 隐藏诊断字符串；自定义状态供应器的 `diagnostic()` 仍由宿主负责内容准入。

`successful()` 保持旧语义：只检查退出码与 timeout。AGT 仍须自行检查截断；SDK-02 新增独立回收状态；不能仅据此旧方法宣布安全交付完成。

### 能力维度

```java
SandboxCapabilitySupport support = client.capabilities()
        .support(SandboxCapability.PROCESS_TREE_TERMINATION);
SandboxGuarantee guarantee = support.guarantee();
String mechanism = support.mechanism();
```

`dimensions()` 是不可变快照。保证级别包括 `KERNEL`、`BEST_EFFORT`、`UNSUPPORTED`、`UNKNOWN`。旧 capability 构造器的所有新增维度默认 UNKNOWN，不能依据平台猜测自定义后端能力。

这些字段描述强制机制，不代表当前请求已经通过预检、进程已经回收或平台已完成原生验收。Windows 默认后端不支持严格读取与网络拒绝；macOS 后代监督和临时目录删除是 best-effort。Windows 内存与进程数限制描述现有 Job 的宿主配置，尚未提供逐请求资源限额准入 API。旧 `FULL` 也不代表 VM 隔离或全部资源都有硬限制。

本轮证据和未完成任务见 [AGT 维护进度](agt-maintenance-progress.md)。

## SDK-02：执行句柄、取消与回收

异步接口仍保持 Java 8 兼容。工作池由宿主提供，必须使用有限队列和 `AbortPolicy`，SDK 拒绝无界队列及 `CallerRunsPolicy`，避免提交操作在调用线程执行工具。

```java
ThreadPoolExecutor workers = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<Runnable>(16),
        new ThreadPoolExecutor.AbortPolicy());
SandboxClient client = SandboxClient.builder()
        .cleanupGrace(Duration.ofSeconds(2))
        .build();
SandboxExecution execution = client.start(request, workers);
String executionId = execution.executionId();
execution.cancel();
SandboxCompletion completion = execution.await(Duration.ofSeconds(3));
SandboxCleanupResult currentCleanup = execution.cleanup();
boolean deliverable = completion.completeSuccessful();
// currentCleanup.confirmed() 为 false 时，宿主保留租户执行容量并协调回收。
// 宿主负责关闭或复用 workers；关闭工作池本身不证明沙箱资源已回收。
```

新增类型均位于 `api` 包。`SandboxExecution` 提供 `cancel()`、只读 `completion()` 快照、有界 `await(Duration)`、`await()` 以及独立 `cleanup()`；没有可由调用方随意 `complete/cancel` 的公开 Future。

- `completion.reason()` 区分 `EXITED`、`TIMEOUT`、`CANCELLED`、`FAILED`；非零目标退出码仍属于 EXITED，退出成功与否另看 result。
- `cleanup()` 分别报告进程、管道、SDK 临时文件与宿主资源的 `CONFIRMED / NOT_REQUIRED / UNKNOWN / FAILED`，同时报告后台执行是否结束。
- `completion.cleanup()` 是交付时的不可变快照。后台管道稍后退出时，`execution.cleanup()` 可以更新，而旧 completion 不会被覆盖。
- `completeSuccessful()` 同时要求零退出、未超时/取消、无截断、无错误，以及所有资源已确认回收。旧 `SandboxResult.successful()` 语义不变。
- `cancel()` 只请求停止；返回 true 不意味着已回收。等待方法被调用方中断，或 `await(Duration)` 提前超时，只终止等待，不隐式取消执行。

超时从句柄创建时开始，包含排队、目录准备、实际 profile 探针、启动和执行。统一清理宽限默认 2 秒，允许配置大于零至 30 秒。取消后的有界交付以该清理期限为截止点，未完成部分报告 UNKNOWN；超时后的有界交付使用原 deadline 加宽限。正常进程退出后的管道排空也只使用共享宽限，不为 stdout、stderr、stdin 分别叠加等待。

取消使用控制标志。监督器和 Windows 原生等待短轮询该标志；deadline 线程不会同步关闭管道、销毁进程或执行任意用户的中断 I/O hook。启动或文件系统调用卡住时，句柄仍交付 UNKNOWN，实际后台工作可能继续；资源状态不能据 Future/句柄交付完成推断为已释放。传统 `execute` 继续是阻塞接口，有界交付应使用 `start`。

### 平台强度和扩展后端

macOS 仅通过 Java 观察后代，Java 8 下没有 ProcessHandle，且 JDK 17/21 也不能证明未观察到的脱离后代已结束。因此当前 macOS 的进程树回收保持 UNKNOWN；目标 root 已结束或管道已关闭都不能提升成完整树保证。

Linux 使用 PID namespace 边界；Windows 只有在原生 `QueryInformationJobObject` 确认活跃进程数为零、root 也结束时，才确认受监督 Job 回收。Windows 的专用账户 worker 与默认原生 launcher 使用同一停止信号和宽限；管道实际 I/O 线程负责关闭句柄，监督器只请求取消 I/O。Windows desktop/ACL 回滚和普通 handle 关闭仍缺少完整证据，宿主资源保持 UNKNOWN，不能宣称全面回收成功。

原有自定义 runner 的 `execute(request)` 继续可用。新增默认 `execute(request, control)` 在进入前检查信号；后端可覆盖该方法并轮询 `SandboxExecutionControl`，通过 `reportCleanup` 明确上报真实回收证据。SDK 仍独立判断自己的工作线程是否结束；上报值不能覆盖未完成的 SDK 资源账本。旧后端即使返回成功，也不自动获得回收证据。任何不响应停止的自定义后端，在 deadline 后得到有界 UNKNOWN 交付，不能强制停止其任意 Java 代码。

本机实际验证及未验证的 Windows/Linux 边界见 [SDK-02 验收记录](agt-maintenance-progress.md#sdk-02)。

## SDK-06：严格目标环境与工作区供应

旧构造器和默认 builder 继续使用 legacy 环境。宿主显式选择严格 preset：

```java
SandboxWorkspace workspace = SandboxWorkspace.create(trustedPrivateParent);
SandboxEnvironment environment = SandboxEnvironment.strict(trustedToolBin)
        .allowVariable("APP_MODE");
SandboxRequest request = workspace.request(absoluteTrustedExecutable)
        .executionEnvironment(environment)
        .environment("APP_MODE", "synthetic")
        .build();
SandboxPolicyReadiness check = client.readiness(request);
// 准入通过后执行；宿主保留并绑定原始 request。
// client.execute(request);
// 确认执行及后台资源不再使用工作区后：
SandboxCleanupState removed = workspace.cleanup();
```

`SandboxEnvironment.strict(Path...)` 要求 1–64 个绝对工具目录，组成完整 PATH，不追加宿主 PATH、空项或当前目录。主程序的实际路径必须位于这些目录内，包括绝对路径和解析后的符号链接；裸名称仍需 `.allowPathSearch(true)`。目录与工作区、任一目标可写根相交时拒绝。严格读取模式自动将工具目录加入有效只读根；HOST 模式已有读取可见性，不额外修改系统工具目录 ACL。宿主必须供应固定、对执行身份只读的工具链，并防止其他进程替换其内容。

严格环境不继承宿主 locale、代理、凭据或语言运行时变量，仅生成固定 PATH、私有目录变量，以及 Windows 必需的 SystemRoot/WINDIR/ComSpec 和固定 `.EXE` PATHEXT。Windows 系统位置来自可信宿主配置。显式请求变量必须先由宿主 `allowVariable(name)` 允许；名称按大小写不敏感方式准入，不会自动读取同名宿主值。未允许变量在任何探针/目标启动前返回 `POLICY_INVALID / ADMISSION`。

PATH、目录变量、ENV/BASH_ENV、LD_*、DYLD_*、Java/JDK 选项、Python/Node/Ruby/Perl/.NET 等已知运行时控制入口不能加入允许列表。新增应用变量仍需宿主审查语义，这不是涵盖所有语言和第三方工具的通用注入检测器。严格模式不负责禁止脚本参数、程序内部 shell 搜索、Windows 的当前目录 DLL/子程序搜索或语言默认模块搜索；固定工具的安全参数和工具链供应仍由宿主负责。

每次执行与预检获得独立随机 temp，严格目标的 HOME/USERPROFILE、缓存位于该 temp 内；默认关闭后不保留 HOME 数据。调用方不能覆盖这些目录变量。`privateTempParent(path)` 只指定宿主父目录，不复用单个 temp。四个内建后端支持此配置；旧自定义 runner 默认拒绝严格环境或自定义 temp，必须显式实现 `supportsEnvironment`，不能静默忽略。

`SandboxWorkspace.create(parent)` 在已存在的可信父目录下创建独立工作区与 temp 父目录；`request(executable)` 自动使用它们，模型无需提供可读写根。POSIX 以原子 0700 创建私有目录；Windows 在交付前固定并检查当前宿主用户 DACL，创建和设置 ACL 之间的安全性依赖父目录已由宿主保护。后端进入前仍可按原有机制授予专用账户/能力 SID 必需权限；Windows ACL 回滚完整性仍为 UNKNOWN。

`workspace.cleanup()`/`cleanupState()` 独立报告 CONFIRMED/FAILED/UNKNOWN，`close()` 尝试清理，调用后须检查状态。初始 UNKNOWN 表示尚未清理；删除失败可见并可重试。它不会自动取消任务或等待后台资源，不能在执行仍使用目录时调用。默认字符串只含状态；路径需显式 getter。temp 回收继续通过执行句柄/预检的 `cleanup().temporaryFiles()` 观察，工作区由宿主独立管理。

删除不跟随符号链接，并检查工作区根的文件身份；无法确认根身份时返回 UNKNOWN。此接口不复制输入、不清洗硬链接、不提供快照，也不能消除父目录被外部重命名的竞态。宿主负责父目录可信性、输入供应与链接审查，不应把已有任意工程直接视为安全工作区。异步任务需结合 SDK-02 的独立回收证据；macOS processes UNKNOWN 和 Windows hostResources UNKNOWN 仍保留。

Java 8 消费示例：`examples/sdk-consumer/src/main/java/example/SandboxStrictEnvironmentExample.java`。传入一个由宿主保护的空父目录；Windows 默认后端仍要求非管理员宿主，专用账户部署须使用显式后端配置。
