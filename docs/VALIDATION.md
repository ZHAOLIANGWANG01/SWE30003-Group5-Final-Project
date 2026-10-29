# 项目验证记录

验证日期：2026-10-29，Australia/Melbourne。验证基线为 GitHub main 的 `485facc9338563bbb744232b7297bd00dcfcf23d` 加本次清理修改；已确认该基线与提供的原始 ZIP 内容一致。

## 编译与自动化测试

使用 Maven Wrapper 3.9.11 执行 `clean verify`，从源码重新编译、运行测试并生成可执行 Spring Boot JAR。实际运行环境为 macOS、OpenJDK 24.0.2，Java 编译目标 release 17。

本机的 Mockito 动态自附加受运行环境限制，因此测试启动时通过 Surefire 的 `-DargLine=-javaagent:<本地 Mockito JAR 路径>` 显式加载 Mockito 5.17.0 agent；没有修改项目依赖或 Mockito 配置。Maven 使用参数 `--batch-mode --no-transfer-progress` 及 `clean verify` 目标。

结果：**33 项测试，失败 0，错误 0，跳过 0；BUILD SUCCESS。** 构建生成 `target/delivery-management-0.1.0.jar`，随后已实际启动。

| 测试类 | 数量 | 覆盖内容 |
|---|---:|---|
| JdbcDeliveryStoreTest | 15 | 原子保存、防重、版本冲突、回滚、金额精度、时段、锁、排班存取、变更提议和并发存取模式 |
| AuthenticationTest | 7 | 登录、CSRF、哈希、初始化、会话、真实业务路径权限、退出、记录所有权和任务分配 |
| FilePersistenceTest | 1 | 文件数据库关闭、重开及重复迁移后数据仍存在 |
| DeliveryLifecycleImplTest | 5 | 状态转换顺序、非法转换、失败保持状态和事件时间顺序 |
| QuoteServiceValidationTest | 3 | 金额、报价时间及有效期输入验证 |
| RequestServiceTest | 2 | 空地址拒绝及客户申请列表过滤 |

源码位于 src/test/java。原始测试 XML 位于本地 target/surefire-reports，属于生成文件，不提交 Git。可分享摘要见 [构建结果](evidence/build-summary.txt)。

## 实际 JAR 启动与 HTTP 检查

使用构建得到的 JAR，在 127.0.0.1:18303 启动，并使用仓库外的独立 H2 文件数据库，避免触碰已有业务数据。Flyway 迁移和演示账号初始化成功。

通过带独立 Cookie 会话及真实 CSRF token 的 HTTP 请求执行 18 项检查，全部通过：

- 四个演示账号登录；客户、办公室员工和配送员业务页面成功渲染。
- 旧交接路径被拒绝，业务路径角色限制生效。
- 空地址被拒绝；有效申请保存；相同提交令牌返回原申请；未报价申请可修改。
- 其他客户不能读取申请；负金额报价被拒绝。
- 员工报价、客户接受、按报价时间分配配送员。
- 员工提议变更时间、客户确认。
- 非法配送状态跳转被拒绝；取件、运输和送达依次成功；客户可查看送达历史。
- 缺失 CSRF token 的 POST 被拒绝；携带 token 的退出成功。

所有输入均为本次检查创建的虚构演示数据。摘要见 [HTTP 检查记录](evidence/smoke-summary.txt)。这次检查验证 HTTP 行为和页面服务端渲染，不包含浏览器视觉检查。

## 本次清理范围

- 删除旧交接页面、对应 Controller 方法及安全配置中的旧路径。
- 权限测试改为检查实际使用的 /requests 和 /scheduling 路径。
- 更新 README、集成/运行/协作文档、项目描述及 CI 步骤名称。
- 移除过期工作台截图，并以本次实际测试结果替换早期验证摘要。

## 验证边界

- GitHub CI 配置使用 JDK 17，其运行结果独立于上述本机验证，以对应提交的 Actions 结果为准。
- 当前 JUnit 测试没有 C 模块的专门业务测试类；HTTP 检查覆盖上述选定流程，不代表所有业务异常、并发场景或浏览器操作均已验收。
- 存储测试会直接构造状态和排班数据，只证明持久化能力，不能当作合法业务流程的证据。
- 最终提交仍需四个业务领域的运行证据，包括正确输入、错误验证、修改或删除输入和成功结果。

## 保留的历史界面截图

以下截图保留自 2026-10-15 的早期本机验证，仅展示登录和退出界面，不证明当前业务流程已验收：

- [登录页](evidence/login.jpg)
- [退出确认](evidence/logout.jpg)
