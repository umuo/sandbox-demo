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

## 目录结构

```text
mkdocs.yml
requirements-docs.txt
docs/
├── index.md
├── architecture.md
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
