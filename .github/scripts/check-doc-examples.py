"""Compile the actual public Java documentation snippets against the Java 8 API.

Only host-supplied inputs are fixtures. SDK classes come from target/classes;
snippets are compiled, never executed, so Windows examples work on every CI OS.
"""
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DOCUMENTS = ("README.md", "docs/index.md", "docs/SDK.md", "docs/policy-model.md",
             "docs/platforms/windows.md")
IMPORT = re.compile(r"^import [\w.*]+;\s*$", re.MULTILINE)
FENCE = re.compile(r"^```java\s*\n(.*?)^```\s*$", re.MULTILINE | re.DOTALL)

# These are the inputs supplied by the embedding application, not SDK stubs.
# Fields allow snippets to declare local values with the same names naturally.
FIXTURES = """
    Path workspace, project, input, output, trustedPrivateParent, trustedToolBin;
    String executable, powershell, modelCommand, modelGeneratedCommand;
    String absoluteTrustedExecutable, trustedExecutable;
    String[] arguments;
    List<Path> currentWritableRoots;
    SandboxClient client;
    SandboxPolicy policy;
    SandboxRequest request;
"""


def main():
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin" / "javac") if java_home else "javac"
    version = subprocess.run([javac, "-version"], capture_output=True, text=True, check=True)
    version_text = (version.stdout + version.stderr).strip()
    flags = ["-source", "8", "-target", "8"] if "javac 1.8." in version_text else ["--release", "8"]
    classes = ROOT / "target/classes"
    if not classes.is_dir():
        raise SystemExit("Build the SDK first: mvn compile")
    generated = ROOT / "target/doc-example-check"
    generated.mkdir(parents=True, exist_ok=True)
    sources = []
    count = 0
    for document in DOCUMENTS:
        text = (ROOT / document).read_text(encoding="utf-8")
        blocks = FENCE.findall(text)
        if not blocks:
            raise SystemExit("No Java snippets found in " + document)
        imports = "\n".join(dict.fromkeys(IMPORT.findall("\n".join(blocks))))
        for index, block in enumerate(blocks, 1):
            body = IMPORT.sub("", block).strip()
            if not body:
                continue  # shared import block, applied to every snippet in this document
            public_class = re.search(r"public (?:final )?class (\w+)", body)
            if public_class:
                name = public_class.group(1)
                source = imports + "\n" + body
            else:
                name = "Doc_" + re.sub(r"\W", "_", document) + "_" + str(index)
                command_type = "CommandSpec" if "SandboxRequest.of(policy, command)" in body else "String"
                source = (imports + "\nfinal class " + name + " {\n" + FIXTURES
                          + "    " + command_type + " command;\n"
                          + "    void example() throws Exception {\n" + body + "\n    }\n}\n")
            path = generated / (name + ".java")
            path.write_text(source, encoding="utf-8")
            sources.append(str(path))
            count += 1
            print(document + ": Java block " + str(index) + " -> " + name, flush=True)
    subprocess.run([javac] + flags + ["-encoding", "UTF-8", "-classpath", str(classes),
                                    "-d", str(generated)] + sources, check=True)
    print("Compiled " + str(count) + " documentation snippets with " + version_text)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
