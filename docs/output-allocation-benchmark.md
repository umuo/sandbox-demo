# O-05 输出分配基准（2026-10-27）

本轮删除内部观察副本：没有回调时不再为每个输出块复制数组；有字节回调时保留一次用户所有权副本，文本解码器同步消费有效输入范围。公共结果、字节回调及文本解码器的防御边界保持；部分块的文本输入仍按有效长度复制，避免读取尾部旧字节。未重写解码器、改变公共签名或修改 worker 协议。

## 方法与范围

基线源码为 `eedbf5cc610bde41010c68a305cf1e17593e007a`。macOS 15.1 arm64、Zulu JDK 8u462，固定 `-Xms128m -Xmx128m -XX:+UseSerialGC`。每个独立 JVM 先预热三次，再测五次；每种场景分别运行三个基线和三个优化 JVM，按 fork 交替先后顺序。最终采样不与本轮 Maven 构建并行。表格各指标取三个 fork 的中位数，吞吐由输入总量/耗时换算。原始样本附有 Java/OS、基线 SHA 和捕获层两个类的 diff，见 [JSON 证据](evidence/output-allocation-jdk8-2026-10-27.json)。

输入是固定 ASCII 合成数据，复用 8 KiB 缓冲；单流保留上限为 8 MiB，分别输入 8 MiB（恰好上限）和 32 MiB（超限继续排空）。使用 RAW/TRUNCATE，测 none、byte、text、both 四种回调组合。包含保留缓冲增长、完成时 `bytes()` 复制及回调/解码分配，不含实际进程、管道、OS 隔离、`SandboxResult` 构造或用户业务处理。callback 的合成消费者把数据存入 volatile sink，防止副本被 JIT 完全消除。单线程分配由 ThreadMXBean 累计计数取得，不把它当作所有后台线程的分配量。

## 结果

数值方向均为基线 → 优化；GC 次数是五次捕获合计，分配是每次捕获 MiB。

| 输入 MiB | 回调 | 分配 MiB/次 | 分配减少 | 吞吐 MiB/s | GC 次数 |
|---|---|---|---|---|---|
| 8 | none | 32.15 → 24.00 | 25.4% | 4066 → 5431 | 4 → 3 |
| 8 | byte | 40.30 → 32.02 | 20.6% | 4019 → 4921 | 6 → 4 |
| 8 | text | 72.91 → 64.77 | 11.2% | 1487 → 1647 | 10 → 10 |
| 8 | both | 81.19 → 72.77 | 10.4% | 1474 → 1549 | 12 → 10 |
| 32 | none | 56.06 → 24.00 | 57.2% | 8911 → 21263 | 8 → 3 |
| 32 | byte | 88.13 → 56.06 | 36.4% | 8923 → 10546 | 13 → 8 |
| 32 | text | 216.95 → 184.75 | 14.8% | 2232 → 2390 | 32 → 27 |
| 32 | both | 248.88 → 216.81 | 12.9% | 2156 → 2325 | 36 → 32 |

32 MiB 输入的无回调场景每次分配从约 56.06 MiB 降至 24.00 MiB（减少 57.2%）；字节回调从 88.13 MiB 降至 56.06 MiB。这里的吞吐是内存捕获微基准，不能作为端到端执行提速承诺。预热有限、JIT、操作系统调度和宿主其他服务会影响计时，不在 CI 设置耗时或提升百分比门槛。

JSON 同时记录 GC 时间和 `heap_pool_peak_sum_bytes`。后者是测量前重置各 heap pool 后、各 pool 峰值之和，不保证这些峰值同时发生，不能称为实际瞬时 heap 峰值或 RSS。32 MiB 无回调的该代理从约 110.5 MiB 降到 70.4 MiB；8 MiB 无回调反而从约 68.4 MiB 到 70.4 MiB，其他若干场景保持约 78.7 MiB。这说明本轮能证明累计分配减少，不能宣称峰值 heap 在所有场景下降。固定 128 MiB heap 下完成全部样本，没有扩大宿主资源消耗上限。

文本路径仍有 decoder 输入合并、CharBuffer 和 String 分配。本轮只移除调用前的冗余副本；进一步复用解码缓冲应作为后续独立测量与修改，不为减少分配而交出内部数组。

## 复现

先编译当前工作代码，然后选择真实 JDK 8：

```sh
export JAVA_HOME=/Users/gitsilence/.sdkman/candidates/java/8.0.462-zulu
mvn -Dmaven.repo.local=/Volumes/Nas/envs/maven/repository test-compile
python3 .github/scripts/benchmark-output.py --baseline eedbf5c --forks 3
```

JDK 路径为本机示例，其他环境应设置自己的 JAVA_HOME。脚本只调用本地 git/javac/java，不下载依赖；生成文件与原始结果均位于项目 `target/output-benchmark/`。它分别编译基线的 LimitedOutput/OutputCallbacks，放到基线 JVM classpath 最前；当前依赖与其余类保持一致。复现时须保留基线 Git 对象与构建后的 target/classes。分配计数不可用时明确失败，不把不可用值视为零。

## 语义与平台验收

新增五项语义测试，在测试内部遍历无回调/byte/text/both、RAW/RETAINED、TRUNCATE/TERMINATE，检查共享额度、精确上限、多余字节、继续排空、有效前缀、UTF-8/UTF-16LE/BE 的跨块及 BOM/无 BOM、重复完成，以及回调篡改数组与输入缓冲复用。保留结果及 SandboxResult 的防御性复制另有断言。原有输出测试与新增语义测试共 21 项通过。多字节观察者回归在修复前复现共享数组污染，修复后通过。

JDK 8/17 完整 verify 各 172 项，0 失败、0 错误、46 跳过；本机平台跳过不计作 Windows/Linux 原生成功。完整远端 CI 的 Windows 默认与 production、Linux、macOS 输出/生命周期场景仍是平台验收依据。无回调优化不更改现有清理 UNKNOWN、准入容量或输出超限原因。

本机额外启用 `SANDBOX_RUN_OUTPUT_INTEGRATION=1`，两项 macOS 原生输出超限用例均通过、无跳过；实际 JDK 8 的 11 个外部消费示例、27 段正文片段编译及严格 MkDocs 构建均通过。原生测试只运行合成命令，不操作宿主业务服务。

### 多个字节观察者的所有权修复

补充回归发现原来的 builder 回调组合会把同一可变数组依次交给多个字节观察者，后一个可能看到前一个的修改。本轮改为扁平、不可变的注册快照，每个字节观察者各取得独立副本；单观察者继续沿用原有调用方式。新增测试同时覆盖 stdout/stderr、RAW/RETAINED、修改后抛异常、继续通知后续观察者，以及 builder 后续注册不改变已构建请求。

该隔离在多观察者场景需要按观察者数量复制；扁平调度避免嵌套复制链。本页分配基准是单个 byte/text 消费者的捕获层对比，不包含 SandboxRequest 的多观察者组合，不将表中收益推广到多字节观察者场景。原始样本的两个捕获类未因该修复变化，因此基准仍适用其明确范围。
