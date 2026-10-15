# 配送管理系统：三人协作规则与接口约定 v1

本规则对应已交付的 A 基础框架。数据库、认证和公共页面已实现；B/C 业务页面和规则仍需开发。三位成员共同审查共享接口后继续开发。

## 1. 固定技术与范围

- Java + Spring Boot + H2 文件数据库 + JdbcTemplate。
- 开发前固定同一个 JDK、Spring Boot 版本及 Maven Wrapper；版本和依赖由 A 维护。接口源码最低支持 Java 17，实际 JDK 还须满足所选 Spring Boot 版本要求。
- 完整实现 Task 1–5：申请、报价、报价决定、排班、配送执行。
- 为排班提供可用时间维护、现有任务查询和冲突检查。
- 付款、管理报告、完整员工管理、真实支付、GPS 和路线优化不纳入本版本。
- 登录、角色权限、持久化、错误提示、修改未确认输入、返回和退出属于支撑功能。
- 在报告中把实现任务对应到申请管理、报价与客户决定、排班与人员分配、配送执行与进度管理四个领域。这是依据小组 SRS 的实现范围划分。

## 2. 文件所有权

| 负责人 | 可以修改的内容 |
|---|---|
| A（数据库负责人） | auth、persistence、项目启动、依赖和配置、数据库脚本、公共接口 contract、公共导航和全局样式 |
| B | requests、quotes 业务类、处理逻辑、页面、模块样式及相应测试 |
| C | scheduling、availability、delivery 业务类、处理逻辑、页面、模块样式及相应测试 |

- B 独占 DeliveryRequest、Parcel、DeliveryQuote 的业务类，以及配送生命周期规则。C 调用 B 的业务方法记录配送结果。
- C 独占 DeliveryStaff、StaffAvailability、DeliverySchedule 及排班和执行协调逻辑。
- 共享的状态值和交换数据使用 contract 中的定义；不要在不同模块另建拼写不同的枚举。
- 一张数据表不必对应一个业务类；contract 的 record 是交换数据，不代替原报告里的领域对象。
- 每个文件只有一位默认修改者。发现别人的问题，提交说明给负责人；经明确交接后可由另一人修复，期间原负责人停止修改该文件。
- 不顺手改动其他模块，不全项目重新格式化，不同时创建功能相同的工具类。

## 3. 推荐目录和网页边界

正式项目统一使用根包 edu.delivery，先确认根包再开发。

```text
src/main/java/edu/delivery/
  contract/       A：DeliveryStore、交换数据、错误码
  auth/           A：登录与角色权限
  persistence/    A：JdbcDeliveryStore、数据库连接与事务
  requests/       B：申请及生命周期业务
  quotes/         B：报价与客户决定
  scheduling/     C：分配、时间变更与客户确认
  availability/   C：配送员和工作时段
  delivery/       C：任务执行与失败处理

src/main/resources/
  db/             A：版本化 SQL 脚本与初始化数据
  templates/auth/、templates/shared/             A
  templates/requests/、templates/quotes/         B
  templates/scheduling/、availability/、delivery/ C
  static/css/common.css                        A
  static/css/requests.css、quotes.css           B
  static/css/scheduling.css、delivery.css       C
```

- 登录路径由 A 管理；申请和报价网页路径由 B 管理；排班、可用时间、时间变更确认和配送网页路径由 C 管理。
- 同一角色可以访问不同模块的页面，不能按角色划分整套页面后重复实现业务。
- Controller 只调用所属业务服务；不要直接调用 DeliveryStore 绕过授权或业务验证。
- 业务代码不出现 SQL、JDBC Connection 或 H2 URL。
- B、C 通过构造方法接收 DeliveryStore；数据库实现只由 A 编写。
- C 配送执行使用 B 实现的 DeliveryLifecycle 接口，以及共同约定的身份权限能力。不要复制一套状态规则。

## 4. 先约定，再修改

1. B、C 列出所需字段、查询条件和业务结果，与 A 一起确认。
2. A 维护 contract 中的方法签名、数据类型、错误码和数据库脚本。
3. 冻结本版接口后，其他成员只调用接口。
4. 改接口前说明原因、影响方法、字段及调用处；由使用者检查后，A 更新接口和变更记录。
5. 同一次接口变更应包括必要的调用代码调整；合并后整个项目必须能够构建。
6. 优先增加独立方法；重命名、删除方法、修改 record 字段时必须一起调整所有调用方。

变更记录模板：日期 / 提出人 / 修改内容 / 原因 / 受影响模块 / 检查人 / 合并提交。

## 5. 简单接口：统一使用 DeliveryStore

完整源码在 src/main/java/edu/delivery/contract。它是项目内部 Java 数据存取接口，不是 HTTP API，也不包含完整业务实现。

| 能力 | 主要方法 | 使用者 |
|---|---|---|
| 登录读取 | findAccountByUsername | A 的认证模块 |
| 申请 | createRequest、findRequest、findRequests、updateRequestDetails | B；C 只读所需申请 |
| 报价 | createQuote、findQuote、findQuotesForRequest、updateQuoteStatus | B；C 读取已接受报价 |
| 配送员和时段 | findActiveStaff、findAvailability、createAvailability、updateAvailability | C |
| 排班 | findCurrentSchedule、findOverlappingSchedules、createSchedule、updateSchedule | C |
| 时间修改确认 | createScheduleChange、findScheduleChanges、updateScheduleChangeDecision | C |
| 配送状态与事件 | saveStatusAndEvent、findEvents | B 的状态业务；C 的执行流程先使用 DeliveryLifecycle 验证 |
| 多步操作 | inTransaction、lockRequest、lockStaff | B、C 业务服务 |

### 通用约定

- ID：long，由数据库生成。不要用 0 或 -1 表示“查不到”。
- customerId、actorUserId、AccountData.id 和 StaffData.userId 都指 users.id；staffId 指 delivery_staff.id，不能把账号 ID 当成配送员记录 ID。
- 查一个：Optional，查不到返回 Optional.empty；查多个：List，没有结果返回空列表。普通存取方法不返回 null；inTransaction 回调无返回值时允许返回 null。
- 金额：BigDecimal，保存为 DECIMAL，统一两位小数，不用浮点数计算金额。
- 时间：OffsetDateTime，数据库使用带时区的时间类型，网页按 Australia/Melbourne 展示。报价有效期依据服务端时间判断。
- version：创建后为 0；每次成功修改递增。更新时传 expectedVersion；版本不匹配报 STALE_DATA，不覆盖别人刚保存的内容。
- 用户密码只存哈希；AccountData 的 passwordHash 仅供认证模块使用，不能返回给网页或记录到日志。
- 查询授权和合法状态转换由业务服务负责；存储层仍执行外键、唯一性、非空、版本检查等数据完整性约束。
- 数据库内部异常转换为 StoreException；不能吞掉异常并返回假成功。
- 核心字段不得为空，handlingNotes 和 event.note 可用空字符串表示无备注；业务层和存储层分别验证自己负责的约束。
- 三个角色仅使用统一的 CUSTOMER、OFFICE_STAFF、DELIVERY_STAFF 值。
- 状态和事件类型固定在 DeliveryData 中。Cancelled 保留为原设计状态，但本版本没有正式取消接口；新功能另行讨论。

## 6. 必须一起保存的操作

- 新申请和 Parcel 一起保存，失败一起回滚；createRequest 自身原子。
- createRequest 使用提交令牌 submissionKey 防重复点击；相同客户、相同令牌且相同内容返回同一申请，内容不同则报冲突。相同字段但不同令牌不自动判成重复业务。
- 创建报价和请求变成 Quoted，在同一个事务内完成。
- 接受报价和请求变成 Accepted，在同一个事务内完成。
- 分配前检查可用时间与重叠任务，然后保存排班，在同一个事务和同一配送员锁保护下完成。
- 配送状态和对应事件一起保存；saveStatusAndEvent 自身原子。
- 时间变更获客户确认后，再检查冲突并更新正式排班，全部成功才提交；待确认时保留原安排。
- 时间提议保存 baseScheduleVersion；客户确认时若原安排已变更，该提议失效并重新提交，不能覆盖较新的安排。
- 即使更新有 version，两个新分配也可能同时通过重叠查询，因此排班要同时使用事务和配送员锁。

A 可用 Spring TransactionTemplate 实现 inTransaction，所有存取必须参与同一事务。锁使用事务内的数据库行锁；只做普通 SELECT 不够。

### 固定加锁顺序

需要排班或时段修改时：先按 ID 升序锁定所有受影响配送员，再按 ID 升序锁定申请，然后查询、验证、保存。改变配送员分配要锁定原人员和新人员。只修改申请或报价时，可以只锁申请。事务结束后释放锁；不要在持锁期间等待用户点确认。

业务层仍负责检查有效接受、合法转换、时段、重叠和权限。事务负责保存的一致性，不代替业务验证。

## 7. 数据库所有权与初始化

- 全部 SQL、表结构、存取实现由 A 修改。B、C 提需求，不另建自己的数据库实现。
- 三人各自使用本地 H2 文件，通过同一套脚本初始化。
- 已合并的建表版本不直接覆盖；结构修改增加新迁移脚本。
- 第一次运行建立表和演示账号；正常启动不清空数据、不重复初始化。
- 数据库文件、编译产物和个人 IDE 设置不提交 Git。共享测试数据使用脚本。
- 第一阶段 A 交付连接、账号读取、申请创建查询和基本事务；随后补齐报价、排班和事件接口。
- B、C 可以先使用仅用于测试的替代存储，最终演示必须连接真实 H2 实现。

## 8. 分支、评审和合并

- main 保持能构建和运行；三人均不直接在 main 开发。
- A 使用 feature/database-auth；B 使用 feature/requests-quotes；C 使用 feature/scheduling-delivery。
- 每次完成一个小功能：本地构建和相关验证 → 同步最新 main → 由另一成员评审 → A 逐个合并 → 验证受影响流程。
- 评审者：A 的修改由 B 或 C 检查；B 的修改由 C 检查；C 的修改由 B 检查。公共接口三人共同确认。
- 不在他人的开发分支上直接提交，不强制覆盖共享分支。
- 遇到冲突先由文件负责人判断，保留两边有效功能；不要直接选“全部保留我的版本”。
- 一个合并失败时先修复 main，再合并下一个功能。
- 文件所有权降低冲突风险，但不能保证没有合并冲突或逻辑错误。

## 9. 每个模块完成的条件

1. 主流程能够完成，错误输入和业务异常能够正确处理。
2. 权限检查实际生效，不能只隐藏按钮。
3. 数据能保存并在重启后读取；失败不留下半成品。
4. 相关测试通过，保存截图或演示片段。
5. 最终类图、代码和模块说明一致。
6. 本人记录修改/保留理由、实际投入时间、贡献和评审记录。

依次验收：登录与申请 → 报价与接受 → 无冲突排班 → 配送与失败处理。C 协调整体流程测试，问题由所属模块负责人修复。B 汇总报告和证据，各成员提交自己的设计理由并共同审查。

## 10. 技术参考

- H2 功能与文件模式：https://h2database.com/html/features.html
- Spring JdbcTemplate：https://spring.io/guides/gs/relational-data-access/
- Spring TransactionTemplate：https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html
- Git 合并与冲突：https://git-scm.com/book/en/v2/Git-Branching-Basic-Branching-and-Merging

## 11. 初始变更记录

| 版本 | 内容 | 状态 |
|---|---|---|
| v1 | 文件所有权、统一存取接口、字段、错误和事务约定 | 开发前草案；三人共同确认后采用 |
