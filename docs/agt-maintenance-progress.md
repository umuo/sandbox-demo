# AGT 接入维护进度

本页记录 2026-10-14 开始的维护迭代。原始需求见 [Java AGT 接入需求](agt-integration-requirements.md)；该文档保留为实施前证据，不以修改旧观察替代修复验收。当前改动未发布；前两阶段及跨平台修复已推送，最新确认的基线 CI 为 [015600d / Actions #27](https://github.com/umuo/sandbox-demo/actions/runs/38523189534)，11 组平台/JDK 任务及文档构建通过。

## 当前实施进度

| 需求 | 当前进度 | 尚缺能力 |
|---|---|---|
| SDK-01 | macOS 实际请求预检 API；精确运行时权限修复；强制原生验收 | Linux/Windows 实际策略预检；其他 OS/架构验收；任意工具链依赖证明 |
| SDK-02 | 新增执行句柄、取消、绝对 deadline、独立资源快照；共享监督器及 Windows 等待改造 | Windows/Linux 本机原生证据；Windows 宿主资源完整回滚确认 |
| SDK-03 | 输出超限终止、聚合额度、受限观察回调与完整成功契约 | 新阶段的远端原生验收结果见下文 |
| SDK-04 | 结构化能力及保守 UNKNOWN；CI 扩展 JDK 17/21 和 macOS arm64 | 后端版本、统一 per-policy readiness 结果、扩展请求准入证据 |
| SDK-05 | 默认结果/异常/状态字符串脱敏；错误码与阶段基础；关键入口分类 | 内部旧错误和 Windows worker 协议分类完整迁移 |
| SDK-06 | 未改变环境和资源供应语义 | 严格环境 preset、可观察目录清理、工作区契约 |
| SDK-07 | 描述现有资源能力，不新增硬限制 | 逐请求限额准入与外部资源后端 |

新增默认方法保持旧 `SandboxRunner` 实现兼容，但旧 runner 的 `validate` 明确不支持。旧 capability 构造器的新增维度均 UNKNOWN。异常构造器保持签名兼容，原始消息和 cause 改用显式调试访问；依赖旧文本/getCause 的调用方需迁移。

## macOS 根因与最小修复

在外层沙箱中，固定探针 exit 71；在允许进入 Seatbelt 的宿主环境，原严格 profile 的固定 shell 探针 exit 134。崩溃位于 dyld ignition 初始化，拒绝日志指向根目录自身的读取。单独增加根目录元数据权限不足以修复；精确允许根目录 DIRECTORY vnode 的数据读取后 shell 正常运行。

JDK 随后暴露两处额外需求：声明路径祖先元数据读取，以及 `hw.pagesize_compat`。缺少祖先元数据时 JVM 属性初始化崩溃；补齐后页大小查询失败，增加该精确 sysctl 后合成 Java 程序运行。OpenJDK 的页大小初始化失败路径可对照 [官方 os_bsd.cpp](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/os/bsd/os_bsd.cpp)。

修复没有开启 blanket file-read、全局 sysctl 或任意 Mach 访问，也没有切换 HOST。有效读取范围仍包含原平台运行时根，加上根目录条目可见性、必要祖先元数据及 shell 配置文件；不能解释为完全隐藏宿主路径元数据。

## 验收证据

原生测试仅使用合成 canary、临时工作区、固定 shell、合成 Hello Java 程序和自建回环 HTTP 服务。前期定位出现过失败：初版 shell 修复后三项通过、JDK 一项失败；这些失败用于定位，未转换为跳过或调低断言。

| 环境 | 后端 | 测试 | 执行 / 失败 / 错误 / 跳过 |
|---|---|---|---|
| macOS 15.5 (24F74), arm64, Zulu JDK 17.0.11 | 系统 `/usr/bin/sandbox-exec` / Seatbelt | 四项严格原生验收 | 4 / 0 / 0 / 0 |
| macOS 15.5 (24F74), arm64, GraalVM CE JDK 21.0.2 | 同上 | 四项严格原生验收 | 4 / 0 / 0 / 0 |
| macOS 15.5 (24F74), arm64, Zulu JDK 17.0.11 | 同上 | 最终完整测试（启用原生验收） | 75 / 0 / 0 / 10 |
| macOS 15.5 (24F74), arm64, GraalVM CE JDK 21.0.2 | 同上 | 最终完整测试（启用原生验收） | 75 / 0 / 0 / 10 |
| macOS 15.5, GraalVM CE JDK 21.0.2，外层沙箱 | 同上 | 完整 Maven verify | 75 / 0 / 0 / 16 |

完整宿主测试中，75 项里实际执行 65 项；10 项跳过是 Linux/Windows 原生测试。普通外层沙箱 verify 的 16 项跳过另包括未启用的 macOS 原生测试，不能将跳过计为通过。`mvn verify` 通过 Java 8 release 编译、格式检查、依赖分析和制品打包；存在既有及新增 API 的 Javadoc 警告，不等同于零警告构建。文档 `mkdocs build --strict` 通过。

现有五个外部消费示例使用新构建 all.jar 在 Zulu JDK 17.0.11 / 21.0.4 下分别以 release 17 / 21 编译通过；这不是正式 Central 制品的 Maven 消费回归，也没有运行这些示例的全部业务路径。

可复现命令（由宿主自行设置对应 `JAVA_HOME`，不要从外层 App Sandbox 中运行原生验收）：

```bash
mvn spotless:apply verify
SANDBOX_RUN_PLATFORM_INTEGRATION=1 SANDBOX_MACOS_STRICT_ACCEPTANCE=1 mvn test
python -m mkdocs build --strict
```

系统 sandbox-exec 不提供独立制品版本，本轮以 OS 构建号标识。未进行 Linux、Windows 或 macOS Intel 的本机原生验收；新增 CI 定义也不等同于已取得远端测试结果。

四项原生断言覆盖：预检不创建目标 marker、严格 shell 输出、外部 canary 正文读取和写入拒绝、允许工作区写入、DENY 不接入回环服务而 ALLOW 可以接入，以及宿主声明 JDK 运行合成 Hello 程序。测试不是 VM 隔离证明，也不证明所有可脱离后代均已回收。

后续实现输出终止、严格环境和资源准入。SDK-02 的实现及平台验证状态见下文，不能将接口新增理解为三平台已全部生产验收。

## SDK-02

新增 `SandboxExecution`、`SandboxExecutionControl`、`SandboxCompletion` 与 `SandboxCleanupResult`，保留原同步入口和 Java 8 编译基线。宿主传入有限工作池；排队取消关闭执行入口并移除任务，目标启动次数为零。旧自定义后端默认未知，不因返回成功获得清理保证。扩展后端可通过公开 `reportCleanup` 提供或更新资源证据，SDK 仍独立判断实际后台执行是否结束；外部上报不能覆盖尚在运行的 SDK 资源。

共享监督器使用绝对 deadline 和单一宽限，stdout/stderr/stdin 不叠加独立 5 秒等待。子进程合法提前关闭 stdin 时保留既有正常退出语义；无法确认流关闭时仍独立记录 UNKNOWN，不能获得完整成功。I/O 任务自行关闭流；监督线程不会同步关闭其他线程正在读取的管道。进程销毁在独立受账本跟踪的 daemon 中进行。任意回调、启动或目录准备卡住时，句柄按期限交付 UNKNOWN，后台完成后可查询新快照。

Windows 默认 launcher 和专用账户 worker 都改为短轮询。Job 回收查询 `ActiveProcesses == 0`；root 额外确认结束，包括分配 Job 失败时清理已经创建的 suspended 进程。原生管道的实际 I/O 线程拥有关闭权，监督线程仅请求取消 I/O。参考 [QueryInformationJobObject](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-queryinformationjobobject) 和 [CancelIoEx](https://learn.microsoft.com/en-us/windows/win32/api/ioapiset/nf-ioapiset-cancelioex)。取消请求本身不构成 I/O 完成证明。

SDK 私有 temp 删除结果现在可观察；失败不再吞掉为成功。实际只读私有目录的删除失败用例验证了 FAILED，并在测试结束时恢复权限和清理合成文件；账本失败不会被后续一次手工清理抹去。Windows 的 desktop/ACL 回滚和普通 native handle 关闭未提供完整独立证据，统一报告 hostResources UNKNOWN。macOS 无法证明脱离后代消失，统一报告 processes UNKNOWN，包括 Java 8 缺少 ProcessHandle 的情况。

### 实际验证

环境均为 macOS 15.5 (24F74) arm64。JDK 8 为 Zulu 1.8.0_282，JDK 17 为 Zulu 17.0.11，JDK 21 为 GraalVM CE 21.0.2。Seatbelt 使用系统 `/usr/bin/sandbox-exec`，以 OS 构建号标识后端版本。

| 验证 | 执行 / 失败 / 错误 / 跳过 |
|---|---|
| 实际 JDK 8 `mvn verify`（默认不开原生验收） | 91 / 0 / 0 / 21 |
| JDK 8 macOS 原生：原有后端 + 新生命周期 | 5 / 0 / 0 / 0 |
| JDK 17 完整宿主测试（含严格模式和生命周期） | 91 / 0 / 0 / 12 |
| JDK 21 完整宿主测试（含严格模式和生命周期） | 91 / 0 / 0 / 12 |
| 格式及依赖检查（实际 JDK 8 verify） | 通过；文档结果见下文 |

单元/共享监督器测试覆盖：私有 temp 删除失败、扩展后端回收上报不能伪造 SDK 工作结束、排队取消、排队期限、容量拒绝、不响应停止的旧后端、阻塞准备阶段、非消费 stdin、后台子进程持管道、任意原文回调阻塞及交付后独立资源快照更新。共享监督器直接进程测试不是 OS 隔离证据。

macOS 原生新增三项覆盖运行中取消、stdin/后台管道阻塞、原文回调阻塞；上一阶段严格读写和回环网络对照测试仍保持。Windows 新增专用账户 Job 原生验收和 accounting structure ABI 测试；Linux 生命周期验收使用同一 POSIX 测试类。Linux/Windows 原生测试本机未执行，CI 新配置尚无远端结果，不能声称已通过。Windows 还缺少默认 token 后端的新生命周期专门原生证据；现有默认后端测试不等价于新增取消验收。

`start` 的交付状态和后台回收状态分开；如果业务要求全面确认，当前 macOS processes UNKNOWN、Windows hostResources UNKNOWN 均应拒绝并交给宿主协调器处理。取消不是业务副作用的事务回滚。此迭代没有实现 SDK-03 输出超限终止或 SDK-06 严格环境。

复现命令：

```bash
# 使用实际 JDK 8 运行
mvn verify
# 使用正常宿主终端的 JDK 17/21 运行
SANDBOX_RUN_PLATFORM_INTEGRATION=1 SANDBOX_MACOS_STRICT_ACCEPTANCE=1 SANDBOX_RUN_LIFECYCLE_INTEGRATION=1 mvn test
# JDK 8 上的本轮 macOS 生命周期验收
SANDBOX_RUN_PLATFORM_INTEGRATION=1 SANDBOX_RUN_LIFECYCLE_INTEGRATION=1 mvn -Dtest=SandboxNativeLifecycleTest,MacOsSeatbeltSandboxRunnerTest test
```

本轮新增 `SandboxCancellationExample.java` Java 8 取消消费示例 的源码位于仓库 `examples/sdk-consumer`。包含该示例的六个外部消费源码，在实际 JDK 8 及 JDK 17/21 的 Java 8 编译模式下均编译通过；实际 JDK 8 运行新示例也通过，返回 CANCELLED，并明确输出 macOS processes UNKNOWN、streams/temp/host CONFIRMED，未打印命令参数、输出正文或工作区路径。这是本地新构建 all.jar 的消费证据，不是新版本已发布的声明。

文档站最终 `mkdocs build --strict` 通过。实际 JDK 8 verify 包含打包、格式检查和依赖分析；仍有 Javadoc/制品重叠等既有警告，不声称零警告或 Sonar 扫描通过。本阶段没有提交、推送、发布，也没有操作本机账户或防火墙。

## 跨平台 CI 修复（2026-10-14）

首次推送 `03c97c9` 的 Actions #23：文档与 macOS Intel JDK 17/21 通过，其余 9 个测试任务失败。修复单元测试对宿主默认读取/联网策略的依赖；严格 profile 测试显式选用 DECLARED_ONLY。macOS 原生超时测试扩大启动预算并断言目标实际启动；Windows 普通验收扩大 worker 启动预算，短期限允许在启动阶段返回类型化 TIMEOUT，取消验收延长标记等待并补充诊断。Linux 原生验收选择 Ubuntu 22.04，先独立检查 bubblewrap namespace 可用性；宿主失败不能转换为跳过。

本地 Zulu JDK 8 `mvn verify` 与 Zulu 17 完整 macOS 原生测试通过。远端矩阵结果以修复提交对应 Actions 为准；尚未确认通过时不视为跨平台验收完成。

后续矩阵已确认 Linux JDK 8/17/21、macOS Intel JDK 8/17/21 与 ARM JDK 17/21 通过。Windows 原生回收修复：Job 已触发终止时，根进程 TerminateProcess 可能因并发退出而失败；继续在原宽限期内等待根进程句柄发出退出信号，避免过早报告 UNKNOWN（依据 [TerminateProcess](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-terminateprocess) 的异步终止语义）。`859f09b` 的 Windows JDK 8/21 通过，JDK 17 的取消测试等待启动标记 40 秒失败；调整原生验收的启动预算，取消后的两秒清理宽限期保持不变。最终完整矩阵仍以最新修复提交的 Actions 结果为准。

## SDK-03：输出超限终止

新增显式 TERMINATE 与可选聚合保留额度，默认 TRUNCATE/RAW 保持兼容。共享 stdout/stderr 额度原子计数，先记录 OUTPUT_LIMIT 再调用观察回调；RETAINED 模式仅传递保留前缀。结果新增完整输出成功判断，完成 DTO 继续要求独立清理确认。自动解码的 UTF-8 半字符探测改为有限采样，避免持续合法输出在 EOF 前积累无限缓存。

Windows 两种 launcher 使用同一捕获器；worker 协议 v5 传输配置及输出终止标记。超限后，worker 不等待可能阻塞的原文回调握手来写终止结果；父进程保留 OUTPUT_LIMIT，流回收状态独立呈现。旧自定义后端的新输出配置在准入阶段拒绝，不允许静默忽略。新增 Java 8 消费示例。

原生测试使用合成输出，POSIX 无限输出由请求期限与输出终止双重约束；Windows 使用有限循环保护回归时的 CI 宿主。覆盖同时输出的总额度、超限前回调信号顺序、阻塞回调及后台清理、协议往返和旧版本拒绝。输出额度不等于硬资源限额，macOS processes UNKNOWN 与 Windows hostResources UNKNOWN 的边界保留。

### 本地验收记录

宿主 macOS 15.5 (24F74) arm64，系统 Seatbelt。实际 Zulu JDK 8u282 `verify`：103 项，0 失败、0 错误、25 跳过；Zulu 17.0.11 与 Zulu 21.0.4 启用全部 macOS 原生测试：各 103 项，0 失败、0 错误、14 跳过（Linux/Windows 原生用例）。Java 8 新增输出原生测试单独执行 2 项，0 失败、0 错误、0 跳过。七个消费示例在实际 JDK 8 与 JDK 17/21 的 Java 8 编译模式下通过；新示例在 JDK 8 运行得到 OUTPUT_LIMIT、stdoutBytes=64、macOS processes UNKNOWN，拒绝输出交付。严格文档构建通过。

本机没有执行 Linux/Windows 新阶段原生验收，须由本阶段对应远端 Actions 验证；不把先前阶段的通过当作本阶段证据。Windows CI 新增聚合额度/受限回调和阻塞父进程回调两项原生用例。默认受限 token 在管理员 CI 宿主仍遵循原拒绝/跳过边界，未通过放宽权限来执行。协议 v5 只用于同版本可信 worker，未发布新 Central 制品。

## SDK-04/05：策略准入证据与稳定错误（2026-10-15）

新增 `SandboxPolicyReadiness` 与 `SandboxBackendInfo`，保留 `validate` 和旧 DTO 构造器。Linux/macOS、Windows 默认及专用账户后端的实际策略预检运行固定可信探针，不运行目标 argv/stdin/回调，不接受调用方加载器环境。默认 Windows 严格策略仍在准入阶段拒绝。返回版本、检查时间、能力、错误码/阶段与独立回收证据；未知版本不猜测，UNKNOWN 回收不包装成成功。

内建 Windows 准备、启动、捕获、部署、清理路径补齐结构化错误；worker 协议 v6 传递错误码/阶段并拒绝旧版本和无效枚举。默认异常和 DTO 字符串不含诊断原文，详细信息保留显式调试入口。专用账户 worker 需重新供应同版本运行时，未发布新 Central 制品。

本机 macOS 15.5 arm64：实际 Zulu JDK 8u282 完整 `verify` 118 项，0 失败、0 错误、29 跳过；JDK 21.0.4 全部 macOS 原生测试 118 项，0 失败、0 错误、16 跳过。JDK 17.0.11 在新增最后一项默认错误脱敏单元测试之前运行 117 项，0 失败、0 错误、16 跳过。JDK 8 单独运行新增预检原生用例 2 项，全通过，无跳过。跳过主要是其他平台或未启用的原生用例，不算通过。

CI 新增 `target/test-evidence.json` 与 Step Summary，汇总实际执行/失败/错误/跳过数、OS/JDK 和真实探针后端版本；不采集请求或诊断正文。SDK 源码测试无 manifest 时版本显式 UNKNOWN。远端本阶段结果待对应提交的 Actions 验证，本机没有执行 Linux/Windows 原生验收。SDK-03 对应提交 `2969be3` 的 [Actions](https://github.com/umuo/sandbox-demo/actions/runs/38525418261) 已确认 11 个平台/JDK 任务及文档构建通过。

同步预检不能硬中断卡住的宿主准备操作，不是动态依赖完整验证、持久授权或完整回收保证。macOS processes UNKNOWN、Windows hostResources UNKNOWN 边界保留；SDK-06 目标严格环境与 SDK-07 硬资源准入尚未实现。

本阶段八个外部消费示例在实际 JDK 8 和 JDK 17/21 的 Java 8 编译模式下通过；JDK 8 运行 `SandboxReadinessExample` 得到 ready=true，目标 marker 未生成，macOS processes UNKNOWN、其他回收维度 CONFIRMED。严格文档构建通过。

## SDK-06：严格目标环境与工作区供应（2026-10-15）

范围与验收：保留 legacy 默认行为；严格 preset 不继承宿主 PATH/秘密，显式允许应用变量，拒绝已知加载器与语言运行时控制入口。宿主配置固定只读工具目录，主程序解析结果不得越界或落入可写目录。每次执行独立 HOME/cache/temp；工作区供应接口报告清理失败及未知身份。输入供应、硬链接清洗、父目录保护和工具链不可变性由宿主负责；不扩大 Windows 读取隔离或资源硬限制保证。

方案 spike：复核现有 EnvironmentPolicy、ExecutableResolver、PathPolicyValidator 和平台 ACL/worker 路径；参考 [Bazel 沙箱供应](https://github.com/bazelbuild/bazel/blob/master/src/main/java/com/google/devtools/build/lib/sandbox/LinuxSandboxedSpawnRunner.java) 的独立执行根/临时目录职责划分。采用现有后端与 Java 8 NIO，避免引入整个构建框架。Java 8 的 [Files.createTempDirectory](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html) 支持原子设置 POSIX 属性；Windows 使用 NIO DACL 设置并读回检查，要求父目录由宿主提前保护。新增 API 是本 SDK 的环境准入和生命周期约定，不能由第三方通用目录工具代替。

新增 `SandboxEnvironment.strict(...).allowVariable(...)`、`SandboxWorkspace` 和 `privateTempParent`。四个内建后端复用同一环境准入、工具解析与独立目录准备；Windows worker 已传递最终有效环境，无需再次修改协议。旧自定义后端默认拒绝新配置。严格读取会加入工具只读根；HOST 已有可见性，避免为环境配置修改系统目录 ACL，宿主需确保工具对执行身份只读。目标自身的语言导入、shell/DLL/子程序搜索仍需固定工具安全参数。

新增单元验收覆盖变量拒绝、固定 PATH、主程序及链接逃逸拒绝、工具与可写根重叠拒绝、并发 HOME/temp 不共享、工作区独立性、链接删除边界、删除失败重试和根身份替换 UNKNOWN。CI 明确注入一个合成父进程秘密，原生目标检查其没有继承，并检查私有 HOME、temp 删除及合成工具目录的写入拒绝。测试不会尝试写系统工具目录。

本机 macOS 15.5 arm64：Zulu 17.0.11 全部原生测试在最后三项用例加入前为 129 项，0 失败、0 错误、16 跳过；Zulu 21.0.4 最终原生测试 132 项，0 失败、0 错误、16 跳过。实际 JDK 8 专项测试在根身份用例加入前执行 13 项，全部通过无跳过。九个外部消费示例在实际 JDK 8 与 JDK 17/21 的 Java 8 编译模式下通过；JDK 8 运行新示例得到 exitCode=0、workspace cleanup CONFIRMED，未打印环境正文。严格文档构建通过。

SDK-04/05 提交 `fc5e254` 的 [Actions](https://github.com/umuo/sandbox-demo/actions/runs/38661041090) 已确认全部 12 个任务通过；本阶段 Linux/Windows 原生结果需以本阶段提交对应的新 Actions 为准。SDK-07 硬资源准入尚未实现；macOS processes UNKNOWN 和 Windows hostResources UNKNOWN 的独立回收边界继续保留。

最终实际 Zulu JDK 8u282 `mvn verify`：132 项，0 失败、0 错误、31 跳过（默认未启用原生验收）；包含打包、格式及依赖分析。JDK 17 格式检查与严格文档构建通过。

补充删除边界审查：工作区与执行 temp 共用不跟随链接的删除器，Windows 显式检测全部 reparse point，在进入 junction 前删除链接本身。新增 Windows junction/外部合成 canary 验收，避免仅依赖 `Files.isSymbolicLink`。根身份被替换时仍返回 UNKNOWN，不删除替代目录。
