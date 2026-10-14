# SDK 发布

## 本地与内部仓库

本地安装：

```bash
mvn clean install
```

验证外部 Maven 项目能正常消费：

```bash
mvn -f examples/sdk-consumer/pom.xml clean package
```

发布到企业 Nexus/Artifactory 时，不要把仓库密码写进 POM。把 server 凭据放在 Maven `settings.xml`，然后使用组织批准的 `distributionManagement`，或者由 CI 指定：

```bash
mvn clean deploy \
  -DaltDeploymentRepository=internal::default::https://repo.example.com/releases
```

## Maven Central

发布坐标为 `io.github.umuo:agent-sandbox-sdk`，Java 包名保持 `io.github.sandboxdemo.*`。项目使用 MIT 许可证。
首次发布版本为 `0.1.0-alpha.1`；主仓库保留开发版本 `1.0.0-SNAPSHOT`。`0.1.0-alpha.1` 已公开发布到 Maven Central，并已从公共仓库下载核对全部制品与签名。

本地凭据统一保存在 `~/.config/maven-central/publishing.env`，提供
`CENTRAL_USERNAME`、`CENTRAL_PASSWORD`、`MAVEN_GPG_KEY` 和 `MAVEN_GPG_PASSPHRASE`。
`.mvn/central-settings.xml` 只引用环境变量，不保存凭据原文。

本地发布使用 Maven 3.9.9 或兼容的更新版本。

在独立发布副本中把根 POM 和 consumer 示例的版本改为目标版本，然后执行：

```bash
source ~/.config/maven-central/publishing.env
mvn -B -ntp -s .mvn/central-settings.xml -Pcentral-artifacts clean install
mvn -B -ntp -s .mvn/central-settings.xml -f examples/sdk-consumer/pom.xml clean package
mvn -B -ntp -s .mvn/central-settings.xml -Pcentral-artifacts,central-upload deploy
```

`central-artifacts` 在 verify 阶段使用 BC signer 签名；常规构建仍生成 sources 和 Javadoc。
`central-upload` 拒绝 SNAPSHOT，并跳过 CLI `all.jar`，只上传 SDK、POM、sources、Javadoc 及签名。
上传配置为 `autoPublish=false`、`waitUntil=validated`；校验通过后在
[Central Portal](https://central.sonatype.com/publishing/deployments) 检查并点击 Publish。
只有状态达到 PUBLISHED 后才算公开发布。已发布版本不可覆盖。

发布后用空缓存验证下载：

```bash
mvn -B -ntp -s .mvn/central-settings.xml -Dmaven.repo.local=/tmp/sandbox-central-download \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get \
  -Dartifact=io.github.umuo:agent-sandbox-sdk:0.1.0-alpha.1
```

无需密钥的签名前打包检查可以使用 `-Pcentral-artifacts -Dgpg.skip=true clean verify`，
这不会生成有效签名。普通本地构建继续保留部署/演示 CLI `all.jar`。

## 发布顺序

1. 在 Linux、macOS、Windows CI 上执行根项目测试。
2. 执行外部 consumer smoke test。
3. 在 Windows 预发布机执行 setup、真实沙箱集成测试和 uninstall。
4. 固定 JDK、JNA、bubblewrap 和 SDK 版本，生成制品 SHA-256。
5. 发布 SDK/POM/sources/javadoc；CLI `all.jar` 作为单独部署制品保存。
6. Windows 节点停止任务，在维护窗口用同版本 CLI 重新 setup。
7. Agent 启动时调用 `SandboxClient.status()`，未就绪时失败关闭。

## 公钥分发与校验失败

首次签名前将实际签名密钥的公钥分发到 Sonatype 支持的服务器：

```bash
gpg --keyserver hkps://keyserver.ubuntu.com --send-keys YOUR_FULL_FINGERPRINT
gpg --keyserver hkps://keys.openpgp.org --send-keys YOUR_FULL_FINGERPRINT
```

若 Central 返回 `Could not find a public key by the key fingerprint`，
先核对实际制品签名的指纹与公钥一致，并从远端重新获取公钥验证签名。
本地签名有效、服务器可检索不等于 Central 已能取得公钥；等待后重新上传未发布版本。
2026-10-14 重新上传 `0.1.0-alpha.1` 后 Central 校验通过（VALIDATED），部署 ID 为
`f98fa6ad-727e-4a3b-aa13-d65fa18cba57`，现已在 Portal 发布，公共仓库中的 SDK、POM、sources、Javadoc 与最终上传制品一致，PGP 签名验证通过；全新 Maven 缓存下载 SDK 及 JNA 传递依赖也已通过。
本地验收记录和发布包保存在 `target/central-release-evidence/`。
