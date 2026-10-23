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
