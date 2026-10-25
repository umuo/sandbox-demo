# 文档站点开发

文档使用 MkDocs Material 构建，源文件位于 `docs/`，站点配置位于仓库根目录 `mkdocs.yml`。

## 环境要求

- Python 3.9+；
- `pip`；
- `requirements-docs.txt` 中固定版本的 MkDocs、Material 和 PyMdown Extensions。

建议使用独立虚拟环境：

=== "macOS / Linux"

    ```bash
    python3 -m venv .venv-docs
    .venv-docs/bin/python -m pip install -r requirements-docs.txt
    ```

=== "Windows PowerShell"

    ```powershell
    py -m venv .venv-docs
    .venv-docs\Scripts\python.exe -m pip install -r requirements-docs.txt
    ```

## 本地预览

```bash
.venv-docs/bin/python -m mkdocs serve
```

Windows：

```powershell
.venv-docs\Scripts\python.exe -m mkdocs serve
```

默认打开 `http://127.0.0.1:8000`。MkDocs 会监听 Markdown 和配置变化并自动刷新。

## 严格构建

```bash
.venv-docs/bin/python -m mkdocs build --strict
```

输出到 `site/`。`site/` 是生成物，不应作为手写文档编辑。

## Java 8 示例编译检查

Java 片段以 Java 8 为默认 API 基线；正文应给出公共 import，宿主输入如命令、工作区和可信工具路径在片段说明中注明。可运行的完整案例继续放在 `examples/sdk-consumer`，不要在文档中另行维护 SDK 实现副本。

```bash
mvn verify
python3 .github/scripts/check-doc-examples.py
mvn -DskipTests install
mvn -f examples/sdk-consumer/pom.xml clean package
```

先指定所需 JDK 的 `JAVA_HOME`。检查脚本直接提取 README、首页、SDK 指南、策略模型和 Windows 平台页的 Java fenced code，使用构建出的真实 SDK 类编译。它仅补充宿主输入字段，不执行片段、不伪造 SDK API。实际 JDK 8 使用自身 API 编译，较新 JDK 使用 `--release 8`；CI 在现有各平台/JDK 任务中执行此检查。完整类之外的片段共享同页列出的 import。

编译通过不代表目标命令可以在任意宿主执行；路径、身份、后端能力和清理保证仍需对应原生验收。新增包含 Java 代码的文档页时，同步更新 `.github/scripts/check-doc-examples.py` 的文档清单及宿主输入。

## 目录结构

```text
mkdocs.yml
requirements-docs.txt
docs/
├── index.md
├── architecture.md
├── implementation-and-permission-granularity.md
├── policy-model.md
├── platforms/
│   ├── windows.md
│   ├── linux.md
│   └── macos.md
├── security-boundaries.md
├── glossary.md
├── SDK.md
├── SECURITY.md
├── PUBLISHING.md
└── stylesheets/
    └── extra.css
```

## 写作约定

1. 只把当前代码已经实现并测试的能力写成保证；
2. 计划、建议和限制必须明确标注；
3. 平台机制首次出现时链接到术语表或在正文解释；
4. executable、argv 和命令示例分开表达；
5. 安全结论同时说明适用的 threat model；
6. 使用 Mermaid 表示执行链，不用静态截图；
7. 外部参考优先操作系统、内核或上游项目的一手资料；
8. 修改策略语义或后端实现时同步更新能力矩阵与安全边界。

## CI 建议

可增加独立文档 Job：

```yaml
- uses: actions/setup-python@v5
  with:
    python-version: "3.12"
- run: python -m pip install -r requirements-docs.txt
- run: python -m mkdocs build --strict
```

严格构建会把断链、未识别链接和导航遗漏转化为发布前可见问题。

平台集成测试还要区分“代码错误”和“CI 宿主根本不提供该内核能力”：

- Linux 只有 bubblewrap 存在且 user/mount/PID namespace 能实际创建时才执行 enforcement 断言；bootstrap 被 AppArmor/容器策略拒绝时抛 `SandboxBackendUnavailableException`；
- macOS 会先运行生成 profile 探针，托管 runner 以 71/134 拒绝 Seatbelt 时测试跳过；
- Windows 默认 native test 无需 setup；可选 dedicated-account test 必须先执行 elevated setup，并覆盖账户、Firewall、worker 和双 Job Object 链路。

跳过不是发布环境验证。生产发布应另设自托管三平台 runner，并把“本平台 enforcement 测试实际执行、未跳过”作为制品晋级条件。

## Windows 原生验收的两种身份

现有 `test` 矩阵的 Windows 路径负责 elevated setup、专用账户后端及卸载；新增 `windows-default` 矩阵在临时 runner 上创建只属于 Users 的本地测试账户，以该真实普通用户运行默认后端。两条路径均覆盖 JDK 8/17/21，不通过降低默认后端的 elevated 拒绝要求来取得覆盖。

普通用户脚本 `.github/scripts/run-windows-default-tests.ps1` 只导出已提交源码到随机 CI 目录，使用独立 Maven repository，不复制 `.git` 或 runner 用户设置/凭据。可用的 Maven artifact 缓存仅作为独立 repository 的种子；准备阶段运行纯命令行单元测试以补齐 Surefire 依赖，移除准备阶段报告后，普通用户验收以 offline 模式执行。账户密码仅用于本机登录，不写入脚本与证据。测试目录向该用户授予完整控制，供测试修改合成路径 ACL；没有向系统目录扩大写权限。脚本在 finally 中保存报告并清理账户、profile 与随机目录，超时会终止测试进程树。只在一次性 CI runner 执行该脚本。

共用的生命周期、输出、预检和资源用例由 `WindowsNativeTestSupport` 按显式环境选择后端：`SANDBOX_WINDOWS_DEFAULT_ACCEPTANCE=1` 选择默认后端，`SANDBOX_WINDOWS_PRODUCTION_TEST=1` 选择专用账户，两者不能同时设置。默认路径还验证普通用户 token、伪造 `user.name` 不改变私有目录 ACL 主体、默认后端选择、写入/删除边界、策略拒绝和并发工作区独立性。管理员路径单独断言默认后端不能被 elevated 宿主启动。

`.github/scripts/windows-native-required.json` 列出必须执行成功的方法。门禁拒绝缺报告、缺方法、跳过、失败、重复、损坏 XML，以及后端或 token elevation 不匹配；新方法或重命名时应同步审查清单。JSON 证据记录 OS/JDK、身份种类、后端和执行/失败/跳过计数，不含请求正文。默认路径证据在 `target/windows-default/`，专用账户在 `target/`，分别上传 artifact。

```bash
python .github/scripts/check-windows-native-evidence.py --backend production
python .github/scripts/check-windows-native-evidence.py --backend default --directory target/windows-default
python -m unittest discover -s .github/scripts -p 'test_windows_native_evidence.py'
```

门禁不能把 Windows 的 `hostResources UNKNOWN` 提升为完整清理保证；非 Windows 本地测试跳过原生用例也不能满足这两条原生门禁。

### Windows 宿主回收故障验收

`WindowsHostCleanupIntegrationTest` 加入双后端必测清单，当前默认 24、production 17 个方法。新增六项覆盖正常逐项检查、Job 创建/挂入/恢复失败、ACL 准备失败、protected 子路径的 DACL 精确恢复、desktop 创建/关闭和 window-station ACL 撤销，以及关闭失败后继续回收。清理故障先执行真实回收再模拟失败 acknowledgement；测试不会故意泄漏句柄或保留临时 ACE。该内部测试接缝没有环境变量或公共 API 开关。

普通用户报告的 `target/windows-default/cleanup-evidence/` 与 production 的 `target/cleanup-evidence/` 随现有 artifact 上传。XML 仅含后端名、资源枚举和状态，不含路径、SID、请求或输出。缺方法、跳过及失败仍由原有原生门禁拒绝。分类成功不能提升总体 UNKNOWN，worker 内部证据不通过外部宿主成功来代替。
