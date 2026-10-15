# SWE30003 Group 5 · Delivery Management

Java 网页项目的 **A 成员基础框架**：Spring Boot 3.5.16、H2 文件数据库、JdbcTemplate、Flyway、Spring Security、Thymeleaf。Java 源码目标版本 17，建议全组统一使用 JDK 17。

本次提交完成数据库、登录权限、项目配置和共享接口。**Task 1–5 的业务服务和业务页面留给 B、C；首页的模块卡片不是已实现业务。** 本项目没有实现完整作业或代替小组报告。

## 立即运行

在项目根目录运行（macOS/Linux）：

```sh
./mvnw clean verify
./mvnw spring-boot:run
```

Windows 使用 `mvnw.cmd clean verify` 和 `mvnw.cmd spring-boot:run`。首次构建需要网络下载 Maven 和依赖。已安装 Maven 时也可用 `mvn clean verify`。

打开 http://127.0.0.1:8080 。默认只监听本机。打包运行：

```sh
./mvnw clean package
java -jar target/delivery-management-0.1.0.jar
```

每次从相同项目根目录启动；默认数据库为 `./data/delivery.mv.db`。普通重启不会清空数据。IDE 的 Working Directory 也应设为项目根目录。

## 本地演示账号

下面都是公开的课程演示账号，不含真实客户信息。首次初始化的共同密码：`Group5-Demo-2026!`。

| 用户名 | 角色 |
|---|---|
| customer.alice | 客户 |
| customer.bob | 第二位客户，用于越权测试 |
| office.demo | 办公室员工 |
| driver.li | 配送员 |
| driver.sam | 第二位配送员，用于分配测试 |

数据库中只保存 BCrypt 哈希。新配送员初始化时生成未来 14 天中的工作日 09:00–17:00 可用时间，时区为 Australia/Melbourne；再次启动不会覆盖账号、密码或已修改的时段，也不会自动延长窗口。后续维护由 C 的模块提供。

若第一次运行前设置 `DELIVERY_DEMO_PASSWORD`，将使用该密码生成演示账号；此设置不会重置已存在账号。设置 `DELIVERY_DEMO_ENABLED=false` 可关闭账号初始化，但空数据库将没有可登录账号。本项目默认配置用于本地作业演示，不是公网部署配置。

## 分工与接入顺序

| 成员 | 独占职责 | 首先阅读 |
|---|---|---|
| A | 全部 SQL、数据库实现、认证、项目配置和公共接口 | [数据库设计](docs/DATABASE.md) |
| B | requests/、quotes/；Task 1–3；DeliveryLifecycle 实现 | [B/C 接入说明](docs/INTEGRATION.md) |
| C | availability/、scheduling/、delivery/；Task 4–5 和可用时间 | [B/C 接入说明](docs/INTEGRATION.md) |

三人先读 [协作规则](docs/TEAM_RULES.md)。B/C 调用 `DeliveryStore`，不要复制 SQL 或改 persistence/。C 调用 B 实现的 `DeliveryLifecycle`，不要另写状态规则。接口返回 record 是交换数据，不代替领域类。

## A 已交付

- 九张业务表及索引、外键、检查约束；Flyway 版本化迁移。
- `DeliveryStore` 全部方法的 H2/JdbcTemplate 实现。
- 原子申请与包裹保存、提交令牌防重、乐观版本检查。
- 原子状态与事件保存、多方法事务、配送员和申请行锁及固定加锁顺序。
- 报价、员工可用时间、排班、变更提议和配送历史的数据存取。
- 用户名/BCrypt 登录、会话、CSRF、角色路由、默认拒绝和记录所有权检查。
- 登录、角色工作台、退出、公共样式及明确标注的业务模块接入页。
- 演示账号与初始时段；自动化测试、Maven Wrapper、GitHub CI 和团队接入文档。

## B/C 必须完成的业务

- B：有效申请、修正、报价有效性、接受/拒绝/过期/重新报价、生命周期规则和相关网页。
- C：可用时间维护、完整排班冲突规则、客户确认、失败后的重新安排、执行权限和配送网页。
- 两人均须做用户输入验证、对象职责设计、业务测试和截图证据。数据库能保存某个状态不代表该业务转换已被验证。
- 不实现 Payment、管理统计、完整员工管理、GPS 或自动路线优化。

## 文档

- [接口、事务与调用示例](docs/INTEGRATION.md)
- [数据库关系图与字段约定](docs/DATABASE.md)
- [运行、配置及常见问题](docs/OPERATIONS.md)
- [编码规范](docs/CODING_STANDARD.md)
- [验证记录](docs/VALIDATION.md)
- [设计调整记录](docs/DESIGN_DECISIONS.md)

## 配置

| 环境变量 | 默认/用途 |
|---|---|
| PORT | 8080 |
| SERVER_ADDRESS | 127.0.0.1 |
| DELIVERY_DB_URL | jdbc:h2:file:./data/delivery;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=5000 |
| DELIVERY_DB_USER / DELIVERY_DB_PASSWORD | sa / 空（本地嵌入数据库） |
| DELIVERY_DEMO_ENABLED | true |
| DELIVERY_DEMO_PASSWORD | 本地演示初始密码，见上文 |

业务用户账号和数据库连接用户不同。不要让前端直接连接 H2。H2 网页控制台默认关闭。
