# 安全边界与威胁模型

## 先定义“安全”

一个进程沙箱不能笼统地回答“安全吗”。必须明确：

1. 谁是不可信主体；
2. 哪些资源需要保护；
3. 使用哪个内核强制点；
4. 失败时是否拒绝运行；
5. 哪些攻击明确不在边界内。

本项目的目标是限制由 Agent 启动的普通用户态进程树，重点保护宿主文件写入、网络访问、环境秘密和进程生命周期。

## 攻击者能力

假设攻击者可以控制：

- executable 后面的全部 argv；
- Shell、PowerShell、Python、Node 或自定义程序；
- 任意数量的子进程（平台限制以内）；
- writable root 内的文件、目录和脚本；
- stdin 内容；
- stdout/stderr 中的恶意或超长数据；
- 路径遍历尝试、符号链接、junction、reparse point 和 hard link 尝试；
- 后台运行、daemonize 和 timeout 规避尝试；
- 网络扫描、SSRF 和本地 socket 访问尝试。

## 可信计算基

Trusted Computing Base（TCB）包括：

- 宿主操作系统内核；
- Java Agent 主进程；
- 本 SDK 与 JNA；
- 可选 Windows setup/worker runtime；
- Linux bubblewrap 二进制；
- macOS `sandbox-exec` 与生成 profile 的代码；
- 负责创建 workspace 的服务账户；
- 管理员安装配置。

TCB 中任何组件被篡改，都可能使沙箱失效。因此安装目录、JAR、bwrap 路径和配置环境变量必须由部署系统保护。

## 已强制的共同性质

### 命令无关

策略作用于 OS 资源访问，不依赖识别 `rm`、`del`、`Remove-Item` 或 Python `open()`。

### 路径规范化

请求路径在进入后端前解析为真实绝对路径，并拒绝策略路径中的符号链接组件。Windows 进一步拒绝 reparse point、UNC 和非 NTFS。

### 环境 allowlist

宿主秘密不会默认继承；只有少量系统变量和调用者显式变量进入目标环境。

### 有界 I/O

stdin、stdout、stderr 和内部协议都有大小上限，防止简单内存耗尽或管道死锁。

### timeout

每个请求必须有正 timeout。平台后端在超时后终止受监督的进程树。

### fail closed

缺少后端、策略不支持、setup 不完整或安全状态无法验证时抛出异常，不自动普通执行。

## 平台保证差异

| 性质 | Windows | Linux | macOS |
|---|---|---|---|
| workspace 外禁止写 | 强 | 强 | 强 |
| declared-only 读取 | 不支持 | mount namespace | Seatbelt |
| 网络 deny | 默认不提供；可选 SID Firewall | seccomp socket deny | Seatbelt |
| 后代继承文件策略 | Token/ACL | namespace | Seatbelt |
| 强制回收进程树 | Job Object | PID namespace | 较弱，Java 监督 |
| 资源数量限制 | 进程数、Job 内存 | 未内建 cgroup | 未内建 |

“强”表示在假设内由操作系统强制，不表示可以抵御内核漏洞。

## 读取与写入不是同一问题

防止写入主要保护完整性和可用性；防止读取保护机密性。

### Windows

当前 `WRITE_RESTRICTED` 设计重点限制写。`ReadPolicy.HOST` 允许当前用户（或可选专用账户）可访问的宿主文件被读取。因此即使只能写 workspace，命令仍可能把读取到的秘密：

- 打印到 stdout；
- 写入 workspace；
- 在网络允许时发送出去。

严格 Windows 机密任务必须使用 VM 文件系统边界。

### Linux/macOS

`DECLARED_ONLY` 可以从路径可见性层面隐藏未声明目录。但 runtime root 本身仍需要读取，且错误配置 readable root 会直接扩大数据面。

## 文件系统别名攻击

### Symbolic link

路径名指向另一个路径。策略根中的 symlink 可能把“workspace 内”重定向到宿主秘密。SDK 拒绝策略路径组件中的 symlink。

### Junction / reparse point

Windows 更广义的路径重解析机制。SDK 逐组件拒绝 reparse point。

### Hard link

不同目录项指向同一 inode/文件记录，不表现为 symlink。若攻击者能在 writable root 中预先放入指向敏感文件的 hard link，单纯路径检查可能不足。

工作区供应系统应：

- 使用新建的每任务目录；
- 最好使用独立卷或文件系统；
- 解压上传文件时不保留链接；
- 扫描 hard link 和 reparse metadata；
- 不复用来源不可信的 workspace 快照。

## TOCTOU

Time Of Check To Time Of Use：验证路径和真正启动沙箱之间存在时间窗口。

缓解措施：

- 规范化真实路径；
- 拒绝链接组件；
- Windows 对重要路径持有不允许 delete sharing 的句柄；
- workspace 父目录由可信服务账户控制。

Linux/macOS 仍可能被沙箱外另一个有权限的进程在验证后替换祖先目录。不要让不可信租户共享可重命名的 workspace 父目录。

## 可执行文件与 DLL/库加载

固定 executable 绝对路径只解决第一步。目标程序仍可能：

- 根据 PATH 查找子程序；
- 从工作目录加载插件；
- 使用平台动态库搜索路径；
- 读取配置文件改变行为。

生产建议：

- 使用固定、只读工具链目录；
- 清理 PATH，只包含可信目录；
- 不允许覆盖动态加载相关环境变量；
- 不把 launcher、JDK、bwrap 或 SDK JAR 放入 writable root；
- 对下载依赖执行签名/hash 验证。

## 网络威胁

### 网络 DENY 不等于数据绝对安全

命令仍可能把秘密写入允许输出，等待其他进程发送；也可能利用未覆盖的 IPC 通道。因此文件读取面和环境变量面同样重要。

### 网络 ALLOW

会引入：

- SSRF；
- 内网服务访问；
- 云 metadata service；
- 数据外传；
- 恶意依赖下载；
- 包管理器 install script。

需要联网时优先使用域名代理 allowlist，而不是全开放。当前 SDK 的 `ALLOW` 是粗粒度开关，域名级控制应由外部 egress proxy/Firewall 实现。

### Unix socket

Linux 无论 ALLOW/DENY 都通过 seccomp 拒绝 AF_UNIX，避免 Docker、D-Bus、SSH agent 等高价值宿主 socket。

## 进程生命周期

### Windows

Job Object 是强内核边界。目标先 suspended 创建、加入 Job 后恢复，减少抢跑窗口。

### Linux

PID namespace、bubblewrap PID 1 和 `--die-with-parent` 共同管理后代。

### macOS

Seatbelt 限制由后代继承，但回收依赖 Java 观察进程树。快速 daemonize 的竞态强度低于 Job/PID namespace。

## 资源耗尽

当前保护：

- 所有平台：timeout、输出上限、stdin 上限；
- Windows：活动进程数和聚合 Job 内存；
- Linux/macOS：未直接配置 CPU、内存、PID、磁盘配额。

生产部署还需要：

- Linux cgroup v2 / systemd unit limits；
- macOS launchd 或服务级资源限制；
- 磁盘 quota；
- Agent 并发队列和租户配额；
- 日志、临时目录和 workspace 容量告警。

## 明确不防御

- 内核、驱动、固件或 hypervisor 漏洞；
- Administrator/root 主动攻击；
- 被篡改的 SDK、JNA、JDK、bwrap 或 setup runtime；
- CPU 微架构侧信道；
- 物理访问；
- 宿主账户本身已泄露；
- 恶意输入造成业务层逻辑错误；
- Windows `HOST` 模式下的数据读取；
- VM 级别的跨租户强隔离需求。

## 什么时候必须使用 VM

以下任一条件成立时，使用一次性硬件虚拟化 worker：

- 执行下载的未知 native EXE；
- 运行可能恶意的 `npm install`、`pip install`、编译器插件；
- 多租户之间必须强隔离；
- Windows 任务要求严格读取白名单；
- 工作负载可以接触生产凭据；
- 不允许任何后台进程逃逸；
- 一次主机失陷的影响不可接受。

VM 中只映射专用 input/workspace/output，不映射用户 home、SSH key、Docker socket 或整个磁盘。

## 上线检查表

- [ ] 使用专用、非管理员 Agent 服务账户。
- [ ] Windows 默认后端由普通非 elevated 用户运行；如启用可选后端，setup 与日常 run 分离。
- [ ] 固定并校验 JDK、JNA、bubblewrap 和 SDK 制品。
- [ ] writable roots 由服务端生成，不接受模型任意路径。
- [ ] readable roots 使用服务端 allowlist。
- [ ] 每任务使用新 workspace，清洗链接和归档文件。
- [ ] Windows 默认网络不受 SDK 限制；需要出站控制时使用外部策略、VM 或显式专用账户后端。
- [ ] 不把 API key 放入显式 environment。
- [ ] 检查 `stdoutTruncated` / `stderrTruncated` / `timedOut`。
- [ ] Windows 升级后重新执行 setup。
- [ ] 在实际 OS 镜像上运行原生集成测试。
- [ ] 配置并发、CPU、内存和磁盘外层限制。
- [ ] 对沙箱代码和部署模型做独立安全审计。

更精确的英文基线见 [SECURITY.md](SECURITY.md)。
