# 详细设计调整记录

以下记录公共基础及当前代码采用的设计决定，供小组报告核对。原设计评价、成员经历、工时和会议记录仍需根据原报告及真实协作补充。

| 原设计/要求 | 实现决定 | 理由与影响 |
|---|---|---|
| A2 初步设计排除 UI 与数据库 | 新增 Spring Boot/Thymeleaf 界面基础、JDBC 持久化、Flyway 迁移 | A3 需要可运行实现；当前业务行为位于 Service，数据通过 record 传递 |
| Customer/OfficeStaff/DeliveryStaff 有不同职责 | users 统一账号，角色和 AccessGuard 支持权限；具体操作由 RequestService、QuoteService、SchedulingService 和 DeliveryService 协调 | 账号、角色与业务职责分离，避免重复认证代码 |
| DeliveryRequest 创建并拥有 Parcel | 两张表，唯一外键；createRequest 在事务内一起保存 | 对应组合关系，防止只保存申请或只保存包裹 |
| 请求有效性由 DeliveryRequest 负责 | 存储层做基础完整性约束，申请验证由 RequestService 执行 | 存储可复用，业务不散落在 SQL 中 |
| 允许用户修正信息 | version 条件更新和原始提交指纹 | 防止旧页面覆盖新数据，重复提交重试不因后续编辑而失效 |
| 排班检查 StaffAvailability 和现有任务 | 提供时段查询、重叠查询、人员行锁和事务 | 普通“先查后写”可能并发双重分配；SchedulingService 在锁内完成业务检查 |
| 客户确认已接受时间的修改 | schedule_changes 保存待确认提议及基准版本 | 未确认时不覆盖现有安排，旧提议不能覆盖新安排 |
| DeliveryRequest 控制生命周期 | DeliveryLifecycleImpl 验证状态转换；DeliveryStore 原子保存状态与事件 | C 复用同一规则，防止非法转换和状态/历史不一致 |
| 高层次 bootstrap 由环境提供初始管理对象 | 应用容器先迁移数据库、建立认证和存取组件，再幂等初始化演示身份 | 本版本不实现经理业务；不启动空请求、报价和排班对象 |
| 可只实现部分系统 | 只为 Task1–5 及必要时段提供存储 | 不创建未选中付款/管理报表的假实现 |

架构：浏览器 → Spring MVC Controller → 业务 Service（通过 record 交换数据）→ DeliveryStore 接口 → JdbcDeliveryStore/JdbcTemplate → H2。身份权限为横向支撑。业务层不能绕过数据入口直接执行 SQL。

报告还应将当前 Service/record 结构与作业 2 的领域类逐项对照，解释职责迁移和保留的关系，并覆盖启动过程及业务场景。以上技术决定不代替完整的原设计质量评价和经验反思。
