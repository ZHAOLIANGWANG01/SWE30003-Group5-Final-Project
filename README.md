# SWE30003 Group 5 · Delivery Management

配送管理网页项目：Spring Boot 3.5.16、H2 文件数据库、JdbcTemplate、Flyway、Spring Security、Thymeleaf。Java 源码目标版本 17，建议全组统一使用 JDK 17。

项目包含配送申请、报价与客户决定、排班与人员分配、配送执行与进度管理四个业务领域，对应 Task 1–5，并提供排班所需的可用时间维护。登录、权限和持久化是公共支撑功能。代码实现范围与验证范围分别见下文和 docs/VALIDATION.md。

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

三人先读 [协作规则](docs/TEAM_RULES.md)。业务模块调用 `DeliveryStore`，SQL 集中在 persistence/ 和初始化代码中。配送执行复用 `DeliveryLifecycle` 状态规则。当前实现使用 Service 承担业务行为、record 传递数据；报告中的类图和职责说明应反映实际代码。

## 已实现的模块

| 模块 | 功能与入口 |
|---|---|
| 登录与权限 | 登录、退出、会话、CSRF、角色和记录所有权检查；首页按角色提供入口 |
| 配送申请 | 客户提交、员工代办、列表与详情、未报价申请修改、输入验证和提交令牌防重；`/requests` |
| 报价与客户决定 | 创建报价、金额和时间验证、客户接受/拒绝、过期判定和重新报价；从申请详情进入 |
| 可用时间 | 新建和修改可用/不可用时段，检查现有安排；`/availability` |
| 排班 | 按已接受报价分配人员、时间冲突检查、重新分配；`/scheduling` |
| 时间变更 | 提议新时间、客户确认或拒绝、确认时重新检查冲突；`/schedule-changes` |
| 配送执行 | 已分配任务、取件/运输/送达、失败原因和事件历史；`/delivery/tasks` |
| 客户进度 | 从“我的安排”进入已排班申请的进度页；`/tracking/{requestId}/events` |
| 持久化 | 九张表、Flyway 迁移、原子保存、事务、乐观版本检查、人员/申请行锁 |

角色路由不代替记录权限检查。失败事件保留当前配送状态并追加历史；允许的后续操作受当前状态和排班规则限制。

本版本不实现 Payment、管理统计、完整员工管理、GPS 或自动路线优化。`CANCELLED` 是保留的状态值，没有对应取消业务接口。

## 验证范围

当前有 33 项自动化测试，覆盖认证、数据存取、文件持久化、申请验证、报价验证及生命周期规则。排班、可用时间和配送执行尚无专门的业务测试类；测试通过不能替代四个领域的完整运行演示。当前验证结果及环境限制见 [验证记录](docs/VALIDATION.md)。

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
