# 构建、运行与维护

## 环境

- 全组建议 JDK 17；源码 release 17。
- Spring Boot 3.5.16；Maven Wrapper 固定 Maven 3.9.11。
- H2/JDBC/Spring Security/Flyway 版本由 Spring Boot BOM 统一管理，查看 `./mvnw dependency:tree`。
- 不需要安装独立数据库服务器，也不需要 Node.js 或 Python 运行项目。

## 常用操作

```sh
./mvnw clean verify
./mvnw spring-boot:run
```

构建产物 `target/delivery-management-0.1.0.jar`：

```sh
java -jar target/delivery-management-0.1.0.jar
```

停止用 Ctrl+C。Windows 将 ./mvnw 换成 mvnw.cmd。首次构建需网络；后续依赖可使用本地 Maven 缓存。

默认只监听 127.0.0.1:8080。遇到端口占用可设置 PORT 或使用 `java -jar ... --server.port=8081`。本地演示账号见 README。

## 正常重启检查

1. 使用业务页面建立申请、报价、排班和配送记录，并记下编号。
2. 正常停止应用，再从同一工作目录启动。
3. 重新登录，查到相同记录和状态。服务器重启后需要重新登录是正常会话行为。
4. 目前 A 的 FilePersistenceTest 已用独立临时文件库自动检查关闭、重新打开和重复迁移后数据仍在。

## 测试数据

- 自动化测试使用独立内存库或 JUnit 临时目录，不触碰 ./data/delivery。
- demo 初始化只增加缺少账号/员工，不重设密码或覆盖已维护时段。
- 想从零演示时，先停应用并备份，然后由本人手动选择删除本地 data；不提供自动清空数据库的网页按钮。
- 更换初始演示密码只在首次创建账号前生效。

## 常见问题

- Permission denied: ./mvnw：macOS/Linux 执行 chmod +x mvnw（Git 已保留执行位）。
- Java 版本错误：核对 java -version 和 ./mvnw -version 的 JAVA_HOME。
- Flyway checksum 错误：恢复已合并的旧迁移，使用新版本迁移；不要随意执行 repair。
- 数据库被占用：本地文件模式只启动一个应用进程；不要在第二个 Java 程序同时打开同一文件库。
- 登录失败：检查账号、初始密码、是否已有旧数据库，以及 app.demo.enabled；无论不存在账号还是密码错误，网页不区分提示。
- POST 返回 403：检查登录、角色和 CSRF token，不要禁用安全检查。
- STALE_DATA：重新读取数据、提示用户并重新确认，不能强行覆盖。
- TRANSACTION_REQUIRED：使用 inTransaction 并按顺序 lockStaff/lockRequest。

## GitHub 协作

先运行 ./mvnw verify，再提交功能分支并发起合并请求。A 统一合并公共文件，业务负责人修改自己的目录。CI 每次提交运行 clean verify。

自动化检查不是完整作业验收：最终提交仍需四个业务领域的完整演示、截图或解说视频及报告材料。当前检查结果见 VALIDATION.md。
