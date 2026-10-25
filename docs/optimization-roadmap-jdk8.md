# JDK 8 分支优化路线

评审日期：2026-10-23。基线：`jdk1.8`，提交 `33765ac`。本文是代码与仓库文档评审结果及后续实施计划；本轮不修改 SDK 行为，不代表独立安全审计或跨平台原生验收。

## 1. 项目现状与判断

项目是面向 Java Agent 的跨平台进程沙箱 SDK。输入为 executable、argv 和显式策略，通过操作系统机制约束目标及后代，不依赖解析命令文本判定安全性。

当前是单模块 Maven 项目：79 个生产 Java 文件、40 个测试 Java 文件、10 个外部消费示例，生产源码约 1.35 万行。对外入口为 `api`、`sdk`；`core` 负责公共执行语义，`platform` 实现平台边界，`demo` 提供 CLI。

```text
SandboxRequest / SandboxPolicy
  → SandboxClient：能力与资源准入
  → ExecutionContext / ManagedExecution：期限、取消、资源账本、结果交付
  → SandboxRunner：路径与环境准备、可信预检、平台执行
  → 输出捕获、进程回收、目录与宿主资源清理
```

| 平台/后端 | 已有机制 | 应保留的实际限制 |
|---|---|---|
| Linux | bubblewrap namespace、seccomp、PID namespace | 当前不供应请求级 CPU、内存、进程数、磁盘硬额度；不支持的要求应拒绝 |
| macOS | Seatbelt profile、真实策略探针、Java 进程监督 | 进程树回收为 UNKNOWN；JDK 8 无 ProcessHandle；不能把 root 退出当完整回收 |
| Windows 默认 | 普通用户 restricted token、capability SID、ACL、Job | 只支持 HOST 读取；默认不提供网络阻断；拒绝 elevated 调用 |
| Windows 专用账户 | 账户、Firewall、worker、Job | 需要 setup 与同版本 worker；宿主资源回收证据仍为 UNKNOWN |

分支已具备 Java 8 编译基线、异步执行和取消、绝对期限、独立清理快照、聚合输出额度、输出超限终止、严格环境、工作区清理与资源准入。Windows 已有请求级 Job 内存和进程数限制。上述功能无需重新立项；下一阶段应优先解决文档契约、默认后端验收、持续运行的资源边界，再进行性能优化和能力扩展。

主要依据：[总体架构](architecture.md)、[SDK 集成](SDK.md)、[历史实施记录](agt-maintenance-progress.md)，以及下文引用的实现文件。历史实施记录有阶段性快照，开头表格不能单独作为当前缺失功能清单。

## 2. 本轮验证范围

| 项目 | 本轮结果 |
|---|---|
| 分支切换 | 已从干净的 master 切至本地 `jdk1.8`，跟踪 `origin/jdk1.8` |
| 存储 | `/Volumes/Nas` 已挂载，检查时约剩余 69 GiB；未创建系统盘依赖缓存 |
| 静态检查 | 阅读 POM、CI、公共 API、执行监督、输出、路径清理及多平台关键实现和测试 |
| 默认 Java | 命令行当前为 Temurin 25.0.3；不等价于真实 JDK 8 运行证据 |
| Maven verify | 离线执行在测试开始前失败：外置盘缓存缺少 `maven-enforcer-plugin:3.5.0`；没有本轮测试通过结论 |
| 文档站 | 当前 Python 无 MkDocs，本轮未执行严格站点构建 |
| 原生/远端验证 | 本轮未执行原生集成测试、未查询远端 CI、未验证 Central 在线状态；历史结果只作为仓库记录引用 |

构建尝试：

```bash
mvn -o -B -ntp -Dmaven.repo.local=/Volumes/Nas/envs/maven/repository verify
```

日志位于本机临时文件 `/tmp/sandbox-roadmap-verify.log`，不作为长期验收制品。离线依赖不完整属于本次环境限制，不能归因于源码构建失败。后续在外置盘准备好依赖与指定 JDK 后重跑。

## 3. 优先事项

优先级含义：P1 为下一迭代优先处理，P2 为基线建立后推进，P3 为需求驱动扩展。本轮没有足够证据认定必须立即阻断发布的 P0 漏洞。性能收益、漏洞可利用性和容量上限均需实测，不能由静态观察推定。

### O-01 · P1：统一 Java 8 文档与示例契约（已确认）

**依据：** POM 为 `maven.compiler.release=8`，但 README、`docs/index.md`、`docs/SDK.md`、`docs/policy-model.md` 中仍有 `Path.of(...)`。这些示例无法直接在 Java 8 编译。`docs/agt-maintenance-progress.md` 开头还有已被后续章节完成的“尚缺能力”，增加接入判断成本。

**实施：** 默认示例统一为 `Paths.get(...)` 并补齐必要 import；明确区分 Java 8 最低运行基线、构建 JDK 和现代 JDK 示例。把维护记录标为历史快照，另设当前能力总表；更新 README 和文档站入口。尽量让正文示例引用经过编译的源码，减少双份维护。

**验收：** 所有标注 Java 8 的可运行片段及 10 个 consumer 示例通过实际 JDK 8 编译；现代写法如保留须显式标注版本；MkDocs 严格构建通过。历史证据保留原始日期与测试范围。

### O-02 · P1：为 Windows 默认后端建立明确的原生验收门禁（覆盖风险）

**依据：** `WindowsRestrictedTokenSandboxRunnerTest.requireUnelevatedTestProcess()` 遇 elevated 进程会跳过；当前 CI 同一 Windows job 还承担管理员 setup。资源原生用例 `WindowsResourceLimitsIntegrationTest` 显式选择 production 后端。配置存在不代表默认后端的每类新能力都有有效执行证据；本轮未读取 CI 实际跳过数。

**实施：** 将管理员安装验收和普通用户默认后端验收拆成明确路径。默认后端用真实普通用户进程运行，覆盖写入拒绝、Job 内存/进程限制、取消、超时、输出额度、环境、清理及并发。保留 elevated 拒绝测试。沿用现有 evidence 脚本，增加预期用例与实际执行数门禁。

**验收：** 分后端记录 OS/JDK/身份类型和执行/失败/跳过数；预期原生用例未执行必须失败，不允许以全部跳过取得通过。保留现有 JDK 8/17/21 与 macOS 架构矩阵。

### O-03 · P1：控制重复阻塞回调造成的累计资源占用（需专项复现）

**依据：** `ProcessExecutor` 每次创建 3 个 pipe worker，并另起 terminator；回调在读取线程内同步调用。`OutputCallbacks.acceptIgnoringFailure()` 可吞异常，但无法结束永不返回的回调。`ManagedExecution` 已能按期限交付并保留 UNKNOWN；这解决了调用方等待问题，未给出进程长期运行时累计后台线程的容量保证。

**实施：** 用合成可释放阻塞回调验证多批次请求：记录交付后仍活动的 I/O 任务、线程、FD/handle 和工作池状态。先建立客户端/宿主级准入、未清理任务阈值及熔断契约；如果引入回调隔离队列，必须有容量、顺序和溢出策略，不得使用无界队列。Java 无法安全强杀任意用户回调，隔离池本身也需要失效处置。

**验收：** 到达容量阈值后新请求明确拒绝，后台任务不会随批次数无限增加；取消交付仍遵守期限；回调恢复后资源归位；被卡住的真实资源仍显示 UNKNOWN。分别验证默认与 production Windows 路径及 POSIX 共享执行层。

### O-04 · P1：补齐 Windows 宿主资源回收证据（已实施分类检查，完整保证仍有限制）

**依据：** `WindowsRestrictedProcessLauncher` 清理处明确把 `hostLease` 完成为 UNKNOWN，注释指出 desktop rollback 和通用 CloseHandle 尚未独立确认。项目已有 ACL ledger 和 lease，需完善证据而非重写整套资源模型。

**实施：** 把 native handle、desktop、路径 lease、ACL 回滚逐项纳入资源账本；记录每项真实完成状态。对创建/分配 Job/恢复目标/撤销 ACL/关闭句柄失败注入故障，验证逆序释放。只有全项有证据才能给出 CONFIRMED。

**验收：** 成功和各失败分支都不遗留临时授权；关闭失败产生 FAILED/UNKNOWN；原始 DACL 保持完整；并发执行之间无相互撤销授权。测试只操作合成目录与专用测试账户。不能通过删除 UNKNOWN 分支来“提高成功率”。

### O-05 · P2：减少输出路径分配与复制（开销已确认，收益待量化）

**依据：** `LimitedOutput.accept()` 在没有消费者时仍调用 `Arrays.copyOf(buffer, observed)`；`OutputCallbacks` 给 byte consumer 又复制一次。最终 `bytes.toByteArray()` 与 `SandboxResult` 构造器继续复制结果。文本解码每块分配输入及 CharBuffer。已有每流/聚合上限，并非无界保留输出，但分配率及峰值内存仍可能明显高于配置额度。

**实施：** 第一小步增加“无任何回调”的零回调分配路径；第二步测量字节/文本回调和大输出的分配热点；必要时复用内部解码缓冲。公共结果和用户回调的数组所有权边界保持防御性复制；任何去复制方案都先明确数据不可变性。

**验收：** 对无回调、byte/text、RAW/RETAINED、TRUNCATE/TERMINATE、UTF-8 跨块/BOM/无 BOM UTF-16 做语义回归。对比总分配、GC、峰值 heap 和吞吐；确认回调修改数组不影响已保留输出或其他消费者。先记录基线，再确定性能目标，不预设提升百分比。

### O-06 · P2：降低监督轮询与反射成本（需基准）

**依据：** `ProcessExecutor` 以最长约 20ms 间隔扫描后代，并逐个再次扫描；`processHandle/safeDescendants/isHandleAlive` 重复查找反射方法。实际开销与进程树规模相关。JDK 8 的 ProcessHandle 缺失是既定能力限制，不应反复当作意外异常处理。

**实施：** 先做 Java 8 兼容的内部进程观察适配器，缓存反射方法及不可用状态；去重扫描并测量活跃与退出后的调度成本。后续再评估线程复用，避免共享池被阻塞回调耗尽。平台内核进程约束和账本语义不变。

**验收：** 短任务、大量并发和有限深度进程树场景有 CPU/线程/延迟对照；取消响应不劣化；真实 JDK 8 仍能加载运行；macOS 回收保持真实 UNKNOWN。

### O-07 · P2：分阶段拆解复杂平台类（维护成本已确认）

**依据：** `WindowsRestrictedProcessLauncher` 1054 行，`WindowsSandboxSetup` 705 行，`WindowsAclManager` 633 行；`SandboxRequest` 605 行。`ProcessExecutor.execute()` 多组重载最终传递大量回调与编码参数。平台清理与启动分支的修改面较大。

**实施：** 在故障测试与原生门禁建立后，按“启动身份/Job 配置/管道捕获/清理账本”提取内部协作者；用内部不可变执行选项对象替代长参数串，保留公共签名。统一公共路径的输出配置映射，减少 probe、target、worker 之间漏传字段的风险。暂不因体量直接拆 Maven 多模块。

**验收：** 每次提交只做一类职责迁移，公共 API 与旧 consumer 不破坏，协议字节格式不变；平台真实测试和失败路径测试全过。无需用行数下降证明质量提升。

### O-08 · P2：让发布和运行证据可追溯（仓库缺口已确认）

**依据：** `docs/PUBLISHING.md` 记录 alpha.2 源码为 `b20d620`，且未创建 Git tag；持久发布证据指向 `target/central-release-evidence/`。consumer 使用本地 SNAPSHOT，CI 当前验证本地 install 后的消费，不能代替已发布制品回归。现有能力与错误 DTO 已可支持脱敏观测。

**实施：** 后续发布绑定版本、源码 SHA、不可变 tag、校验和与 CI artifact；若补历史 tag，先核对真实发布源码。增加从独立 Maven 仓库消费发布制品的冒烟流程。基于 executionId、错误码、阶段与 cleanup 状态输出计数和耗时，避免采集 argv、环境值、输出正文和绝对路径。

**验收：** 能从一个版本追溯源码、SDK/worker 协议、矩阵结果和制品；证据不只存在于可 clean 的 target 目录；发布 consumer 在干净仓库、真实 JDK 8 中通过。仓库记录与在线发布状态分开标注。

### O-09 · P3：按部署需求增加资源后端与原生事件（能力扩展）

**依据：** 已有 `ResourceAdmission`、`SandboxResourceLimits`、结构化能力和隔离等级；Windows 目前支持内存/进程数限制，CPU/磁盘及 POSIX 资源限制仍拒绝。维护记录说明尚未订阅 Windows Job 资源通知，不从退出码猜测 RESOURCE_LIMIT。

**实施：** Linux 先做 cgroup v2 委派与计费语义验证，再选择独立 runner 或扩展后端；明确聚合/单进程内存、累计 CPU/速率、磁盘容量/IO 限流的区别。Windows 评估 Job 通知与资源终止原因映射。macOS 严格硬资源或强隔离需求优先由外部 VM runner 承接；不扩大现有保证。

**验收：** 无权限或控制器不可用时失败关闭；使用有限资源探针并保留受限/不受限对照；限额跨后代生效且回收可查询；每项能力只按实际内核保证声明。没有具体业务需求时不提前投入全平台实现。

### O-10 · P2：稳定 worker 协议演进约束（维护风险）

**依据：** `WindowsWorkerProtocol` 当前 v7，已有 magic、版本、长度及枚举合法性检查；部分策略以 enum ordinal 编码，资源演进已多次升级版本。现有拒绝旧协议机制应保留。

**实施：** 先加入固定字节 fixture 和字段边界的契约测试，覆盖截断、非法长度/枚举及版本不匹配；明确任何字段或枚举顺序变更都需协议审查。下次必要升级时考虑显式稳定 wire ID，避免把 Java 枚举顺序作为隐式协议约定。

**验收：** 同版 host/worker 往返正确、错版拒绝、异常输入不会按声明长度无界分配；不能为了兼容旧 worker 静默忽略新策略。仅为整理代码不升级协议。

## 4. 实施顺序、投入和交付物

以下为单人有效开发日粗估，含针对性测试，不含原生环境/CI 排队及外部审计；按阶段验收后重新估算，不作为固定交期。

| 阶段 | 顺序与依赖 | 粗估 | 可交付结果 / 退出条件 |
|---|---|---|---|
| A：可信基线 | O-01 → O-02，补当前 SHA 测试证据 | 3–5 日 | Java 8 示例可编译；默认/production Windows 有独立有效执行记录；完整 verify 与严格文档构建 |
| B：持续运行边界 | O-03、O-04，依赖 A 的真实门禁 | 5–10 日 | 阻塞回调压力报告、准入阈值；宿主资源逐项回收与失败证据；仍未知的项明确保留 |
| C：性能与维护性 | 先 O-05/O-06 基准，再优化；O-07/O-10 分小提交 | 5–9 日 | 可复现前后对照、输出语义回归、协议 fixture；重构不改变公共契约 |
| D：发布闭环 | O-08，首次正式发布前完成 | 2–4 日 | 版本到源码/测试/制品可追溯，已发布制品消费测试，脱敏运行指标 |
| E：资源扩展 | O-09，业务目标确定后启动 | 先用 2–3 日 spike 估算 | 部署权限与语义报告，再决定实现范围及成本 |

每个事项建议单独建立 issue，记录负责人、基线 SHA、影响后端/JDK、验收命令、证据位置和未关闭风险。O-08 的发布证据整理可在 A 阶段开始，不能等扩展能力完成才建立追溯。

## 5. 验证与容量基线

**通用门禁：** 在实际 JDK 8、17、21 执行 `mvn verify`，编译外部 consumer，执行 `mkdocs build --strict`；保持现有格式、依赖分析与 Javadoc 检查。新增 API 同时验证旧 consumer 的源码/二进制兼容，新增策略继续拒绝旧 SPI 未声明的能力。

**原生门禁：** 沿用 `.github/workflows/ci.yml` 中 platform/lifecycle/output/readiness/environment/resource 开关。Linux、macOS 与 Windows 使用各自原生运行环境；Windows 区分普通用户与管理员安装路径；不能将跨平台跳过计为通过。

**性能实验：** 用合成工作区、有限输出和可释放阻塞回调，分别测短任务、接近额度输出、byte/text callback、多级有限进程树。并发从 1、4、16 逐步增加，记录线程/FD/handle、heap/GC、吞吐、启动/预检/执行/清理 p50/p95/p99 和 UNKNOWN 数量。每组固定 OS/JDK/backend、策略、预热及重复次数；达到宿主资源阈值即停止，不用无界 fork 或内存消耗探针。

**本机资源约定：** 仓库、JDK、Maven/Gradle 缓存和较大的证据文件优先置于 `/Volumes/Nas`；使用前检查挂载与空间。Maven 继续使用 `/Volumes/Nas/envs/maven/repository`。测试只使用合成目录与自建服务，不操作宿主必须运行的业务服务。临时小日志可以放系统临时目录。

## 6. 建议首个实施包

先交付 O-01 的 Java 8 文档修正与编译验收，再补 O-02 的默认 Windows 后端真实执行门禁，同时完成 O-03 的受控压力复现报告。获得可靠基线后，优先做 O-05 的无回调分配优化；大规模平台重构与新增 cgroup/VM 后端放在后续阶段。

第一轮应能回答三个具体问题：调用方复制文档能否在 Java 8 编译；默认后端的承诺是否有实际运行证据；连续异常任务会否耗尽宿主资源。以这三个结果决定后续优化投入。

## 7. O-01 实施与验收（2026-10-23）

O-01 已完成文档与编译门禁改进：README、首页、SDK 指南、策略模型和 Windows 平台页统一使用 `Paths.get` 并给出公共 import；SDK 指南增加当前能力表，维护记录开头改为明确的历史快照；区分 Java 8 运行/API 基线、构建 JDK、本地 SNAPSHOT 与已发布 alpha.2 坐标。新增 `.github/scripts/check-doc-examples.py`，直接编译正文片段并接入现有 CI 矩阵。复现方式见 [文档站点开发](site-development.md)。

| 本轮验证 | 结果 |
|---|---|
| Zulu JDK 8u462 `mvn verify` | 143 项，0 失败、0 错误、34 跳过；含格式、依赖及制品检查 |
| JDK 8 外部 consumer `clean package` | 10 个 Java 消费示例编译与打包成功；没有运行示例业务命令 |
| 正文片段编译 | 26 段分别通过实际 JDK 8u462、JDK 17.0.13/21.0.7 的 Java 8 API 检查 |
| 负向验证 | 在临时文档副本重新引入 `Path.of`，实际 JDK 8 编译按预期失败；未修改正式文档 |
| MkDocs | `build --strict` 通过，无断链提示 |

本轮下载的 Maven 依赖位于外置盘既有仓库，文档虚拟环境位于外置盘项目 `.venv-docs`。本节补充后续实施结果，第 2 节保留最初评审时的环境限制。测试跳过不算通过，本次未运行原生集成测试，也不将本地验证作为远端 CI 已通过的证明。O-02 起的后端验收、资源治理及性能优化仍待后续迭代。

## 8. O-02 实施与验收（2026-10-23）

新增独立 `windows-default` CI 矩阵（JDK 8/17/21），在一次性 runner 上以新建普通用户运行默认后端，不执行 production setup；原 Windows 矩阵保留 elevated setup、专用账户验收与 uninstall。生命周期、输出额度、真实策略预检、资源额度和严格环境场景复用测试主体并显式选择后端，默认路径另验证身份、写入/删除权限、策略拒绝和并发提交独立性，管理员路径验证默认后端拒绝 elevated 调用。

新增必测方法清单和证据门禁：默认后端要求 17 个方法成功，专用账户要求 10 个。缺报告/方法、跳过、失败/错误、重复方法、损坏 XML，以及后端/token elevation 不符均不能取得通过。证据分开上传，包含 OS/JDK、身份种类、后端、实际执行与跳过数量。账户、profile、独立源码和 Maven repository 仅在随机 CI 目录内临时供应并清理；实现与复现命令见 [文档站点开发](site-development.md)。

本地 Zulu JDK 8u462 `verify` 通过：147 项、0 失败、0 错误、38 跳过；其中新增 Windows 原生用例在 Mac 上跳过，不算 Windows 验收通过。证据门禁 7 组正反测试、26 段文档片段编译、工作流 YAML/方法清单检查和严格文档构建通过。Windows 原生结果须以本轮提交对应的远端 CI 为准；O-01 提交 `79a9ef3` 的远端工作流已确认成功，不能替代本阶段验证。


### 普通用户原生门禁发现的身份错误

首轮准备阶段的 C# 探针路径问题已修正为 Resolve-Path/Join-Path；Maven Central 429 限流通过 artifact 缓存种子和普通用户离线执行路径处理。`21280af` 对应原生测试随后报告默认临时目录 `CreateFileW(path lease)` 返回 Win32=5，多项执行未能启动。代码检查发现 `PrivateDirectories` 使用 `user.name` 查找 ACL 主体；该属性可由 `-D` 覆盖，Windows JVM 还可能从继承的 USERNAME 初始化它，不能作为身份授权依据。

修复改为查询真实进程 token 的账户，再配置宿主私有 DACL；不增加 Everyone/Administrators 授权，也不放宽路径租约。新增普通用户原生回归将 `user.name` 设为 SYSTEM，检查 DACL 仍只授予真实 token 用户，并验证合成文件读写/删除；此方法加入强制门禁。具体修复结果仍以对应新提交的三个 JDK 原生 CI 为准。

`cd95d22` 的三个普通用户 JDK 任务不再报告私有目录访问拒绝，身份伪造回归通过；剩余两项旧测试错误地把 CMD 的删除失败等同于非零退出码（实测 stderr 为 Access is denied 而 exitCode=0）。删除/重命名验收改为校验命令已执行、文件内容不变、重命名目标不存在，并保留允许修改和允许删除的对照；PowerShell 的 Stop 语义继续要求非零退出。没有更改权限策略或减少必测方法。最终通过状态仍以修正提交的完整 CI 记录为准。

O-02 最终提交 `1e3ae29` 的 [完整 CI](https://github.com/umuo/sandbox-demo/actions/runs/39900563120) 已确认 15/15 成功，包含 Windows 默认普通用户与专用账户两条 JDK 8/17/21 验收路径。

## 9. O-03 实施与验收（2026-10-24） {#o03-implementation}

### 受控复现

基线源码 `1e3ae29`，本机 macOS 15.1 (24B2083) arm64、Zulu JDK 8u462。`SandboxCallbackAccumulationTest` 使用共享监督器启动固定 `/bin/sh`，以三个可释放 latch 阻塞观察回调，单个工作线程顺序提交三次请求。工作池退出后 active=0、queued=0，但三个回调仍阻塞，完成快照的 streams=UNKNOWN。释放 latch 后三个回调均退出、流租约结束。这是管道监督层证据，不是操作系统隔离证明。

首次独立基线样本：JVM 线程数从 6 到交付后的 12，释放后为 8；打开的 FD 从 32 到 33，释放后仍为 33；观测 heap 从约 32.2 MB 到 51.1 MB。JVM 延迟启动的监督器、进程回收线程和 GC 会影响这些数字，此样本不证明 FD 或 heap 泄漏。确定的缺口是结果交付和工作池空闲未限制仍在执行的回调。测试始终在 finally 释放合成回调，不留下故意卡死的宿主线程。

### 实现

- 新增可共享的 `SandboxExecutionBudget`，默认 class-loader 内共享上限 32，可由宿主显式配置准入域。预算饱和时返回 `EXECUTION_CAPACITY_EXCEEDED/ADMISSION`，不调用后端或启动探针/目标，不排入无界等待队列。
- 排队、同步/异步执行与实际策略预检统一占用预算。工作方法结束且登记租约全部结束时，由非阻塞原子操作归还名额；交付、取消和 UNKNOWN 清理状态不伪造释放。仍然保留终态 UNKNOWN/FAILED 的安全含义，预算空闲不代表完整清理证明。
- 对两个 Windows runner 将路径校验纳入 preparation 租约的 finally 结束路径，避免不存在的工作目录消耗永久名额；准备阶段仍保留宿主资源 UNKNOWN，不因容量归还提升清理保证；worker Job 创建失败纳入已有 finally 清理，普通 ProcessBuilder 启动参数异常也结束未启动的进程租约。
- 未引入额外回调队列或任意强杀。默认/production Windows 和 POSIX 原生回调验收同时检查：阻塞期间反复提交被拒绝、没有目标 marker，释放后预算归零并恢复执行。Windows 必测清单加入无效路径后容量恢复用例，更新为默认 18、production 11 个方法。

### 验收与复现

共享预算为 2 的场景中，两个已交付回调持续占用名额；跨客户端连续 20 次新提交全部拒绝，活动回调保持两个，释放后归零并恢复正常执行。测试同时覆盖同步交付残留租约、取消后的未结束 worker、排队取消/到期、工作池拒绝、准入/后端失败、外部清理报告不能覆盖活动租约，以及最后一个租约与 worker 并发结束的 30 次交错。

```bash
mvn -Dtest=SandboxExecutionBudgetTest,SandboxCallbackAccumulationTest,SandboxExecutionLifecycleTest test
SANDBOX_RUN_LIFECYCLE_INTEGRATION=1 mvn -Dtest=SandboxNativeLifecycleTest test
mvn verify
```

压力采样位于 `target/admission-evidence/`，CI 随各 OS/JDK artifact 上传；包含活动回调数、工作池状态、JVM 线程/heap、可取得时的 FD 数以及显式预算占用；Windows 原生用例另记录宿主 JVM handle 数，不记录路径、argv 或正文。打开 FD 不可查询时为 -1，budget 字段为 -1 仅表示该样本未读取显式预算，不表示无限容量。实际 JDK 8 和 JDK 17 的完整 verify 各 157 项、0 失败、0 错误、40 跳过；本机三项 macOS 原生生命周期测试已通过；本阶段 Windows/Linux 原生与完整矩阵结论以对应提交的 CI 为准。新的消费例使当前 consumer 源码数为 11，旧 10 个例子无需改动调用签名。

宿主仍须控制预算域、class loader 和低层 runner 的使用，不应通过重建预算绕过饱和。此改动约束 SDK 在途工作，不能对用户代码自身的任意分配、未登记后台任务或未观察到的后代提供全局资源上界。平台清理保证与原有 UNKNOWN 边界不扩大。

## 10. O-04 实施与验收（2026-10-25）

基线 `e6d717c` 的 [完整 CI](https://github.com/umuo/sandbox-demo/actions/runs/40084560419) 已确认 15/15 成功。本轮增加固定分类的不可变 `hostDetails()`，保持旧构造器兼容；native close/free、路径 lease、desktop、文件与 window-station ACL、worker session 分别保留真实状态。活动检查继续持有准入名额，FAILED 不会被后续成功覆盖，未观察的分类没有成功默认值。

修复 protected 路径快照可能捕获继承的临时 capability ACE 的时序问题：持久专用账户授权之后、临时授权之前捕获快照；撤销所有临时授权与恢复快照后，再读回检查 SID 已消失。原始 DACL 的有效 ACE 字节及继承保护位均需匹配，不比较未使用的 ACL padding。session 创建授权失败也执行目录清理；关闭失败记录 FAILED 并继续其他回收，避免原有静默吞掉异常。

新增四项跨平台回归及六项双后端原生验收。故障范围涵盖 ACL 准备、desktop 创建、Job 创建/挂入/恢复、各类关闭/撤销 acknowledgement；覆盖 protected 子路径不带回 capability ACE、重复 close、失败粘滞与恢复后再次执行。原生必测清单更新为默认 24、production 17，分类状态 XML 随 CI artifact 上传。原生用例只操作合成目录与随机 capability；清理故障先真实回收再模拟失败结果，不污染 CI 宿主。

JDK 8 与 JDK 17 完整 verify 的本地结果各 167 项、0 失败、0 错误、46 跳过。Windows 原生用例在 macOS 上跳过，真实验收以本轮提交对应 CI 为准。总体 Windows `hostResources=UNKNOWN` 继续保留：JNA 托管分配等尚无完整独立证明，production 协议 v7 仍不报告 worker 内部清理，并显式记录 `REMOTE_WORKER=UNKNOWN`。分类 CONFIRMED 仅确认已登记检查，不扩大隔离或全面回收保证。
