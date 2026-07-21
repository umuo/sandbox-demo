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

## 对外发布前必须补充

当前仓库不知道最终项目所有者和发布地址，因此没有虚构这些元数据。公开发布 Maven Central 前必须由项目所有者确认并补充：

- 正式的反向域名 `groupId`；
- 项目 URL、SCM URL 和 issue tracker；
- 项目许可证以及 `LICENSE`/`NOTICE`；
- 开发者/组织信息；
- CI 中的签名和 Maven Central 发布凭据；
- 不可变正式版本，禁止发布 `1.0.0-SNAPSHOT` 到 release 仓库。

构建已经附加主 JAR、sources JAR 和 javadoc JAR，并在 manifest 中声明 `Automatic-Module-Name: io.github.sandboxdemo.sdk`。

## 发布顺序

1. 在 Linux、macOS、Windows CI 上执行根项目测试。
2. 执行外部 consumer smoke test。
3. 在 Windows 预发布机执行 setup、真实沙箱集成测试和 uninstall。
4. 固定 JDK、JNA、bubblewrap 和 SDK 版本，生成制品 SHA-256。
5. 发布 SDK/POM/sources/javadoc；CLI `all.jar` 作为单独部署制品保存。
6. Windows 节点停止任务，在维护窗口用同版本 CLI 重新 setup。
7. Agent 启动时调用 `SandboxClient.status()`，未就绪时失败关闭。
