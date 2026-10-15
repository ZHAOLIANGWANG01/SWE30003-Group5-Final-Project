# B/C 接入说明：contract v1

## 1. 使用方式

数据库负责人 A 实现 `DeliveryStore`。业务服务通过构造方法注入接口，不实例化 `JdbcDeliveryStore`，不创建第二个数据库连接配置。

```java
@Service
public class YourBusinessService {
  private final DeliveryStore store;
  private final AccessGuard access;
  private final Clock clock;

  public YourBusinessService(DeliveryStore store, AccessGuard access, Clock clock) {
    this.store = store;
    this.access = access;
    this.clock = clock;
  }
}
```

不要把数据库存取接口暴露为通用 CRUD 网页接口；Controller 调用经过验证的业务服务。业务规则属于 B/C，A 负责数据完整性和可靠提交。

## 2. 固定类型

- 共享交换数据、角色和枚举：`DeliveryData`。
- 存取接口：`DeliveryStore`；错误：`StoreException`。
- 状态规则接口：`DeliveryLifecycle`，由 B 实现为 Spring Bean，C 注入使用。A 没有写空的“成功实现”。
- ID：`long`；`customerId`、`actorUserId`、`AccountData.id` 指 users.id；`staffId` 指 delivery_staff.id。
- 金额：`BigDecimal`，正数，最多两位小数。数据库不会静默四舍五入。
- 时间：`OffsetDateTime`。HTML datetime-local 没有时区，解析时明确使用 `ZoneId.of("Australia/Melbourne")` 并转换为 OffsetDateTime。不能把 +10 或 +11 永久写死。
- 查询一个返回 Optional；多个返回空或非空 List，不返回 null。
- 版本：创建时 0，修改成功后加 1。提交用户看到的版本，STALE_DATA 时重新加载并让用户重新确认，不自动覆盖。
- `inTransaction` 回调允许返回 null（无返回值）；普通查询和保存返回值不为 null。

## 3. 权限必须调用

`AccessGuard.current()` 从真实会话取得 ID 和角色，不能信任表单传入的操作者 ID。

- `requireRole(...)`：限定业务角色。
- `requireCustomer(customerUserId)`：本人客户或办公室员工；客户接受报价时，B 还应先限定 CUSTOMER 角色，若允许员工代办则明确写业务规则。
- `requireRequestRead(requestId)`：客户本人、办公室员工，或该任务的有效配送员。
- `requireAssignedDelivery(requestId)`：办公室员工或当前分配的有效配送员。
- `requireStaff(staffId)`：办公室员工或本人配送员。

URL 级角色限制只是第一层。每个业务方法仍要检查记录所有权。列表查询也必须按身份过滤，不能先返回全量数据再靠页面隐藏。

登录页面 POST /login，退出 POST /logout；GET /api/session 只返回安全身份摘要。AccountData 和 DeliveryPrincipal 不得作为接口响应。

## 4. B：申请与报价

1. 客户创建申请：从会话取得 customerId；员工代办：先检查 OFFICE_STAFF，再验证所选客户。
2. 业务层验证联系方式、地址、包裹等。新表单生成 UUID submissionKey，网络重试/重复点击沿用同一 key。
3. 调用 createRequest，一起保存申请与 Parcel；相同客户+相同 key+原始内容返回原申请，内容不同报 CONFLICT。
4. updateRequestDetails 同步修正申请与包裹，不改变状态。允许在哪些阶段修正，以及是否需要作废旧报价，由 B 业务层决定并记录设计理由。
5. 报价业务加申请锁，在同一事务中创建报价与记录 QUOTED 事件。
6. 接受报价：同一事务锁申请、重新读取报价和申请、检查本人/有效期/当前报价/状态/版本，更新报价决定，再保存 ACCEPTED 和 QUOTE_ACCEPTED。
7. 拒绝和过期单独更新报价结果，不能把“拒绝”当成配送状态。多个报价如何失效、重新报价时如何处理旧报价由 B 明确实现。

事务形状示例（省略的 validate 方法必须由 B 实现，不能照抄成无检查流程）：

```java
return store.inTransaction(tx -> {
  tx.lockRequest(requestId);
  var request = tx.findRequest(requestId).orElseThrow();
  // Check identity, request eligibility, expected version and quote rules here.
  var quote = tx.createQuote(draft);
  var event = new EventDraft(EventType.QUOTED, OffsetDateTime.now(clock),
      access.current().userId(), "Quote prepared");
  var next = lifecycle.nextStatus(request, tx.findEvents(requestId), event);
  tx.saveStatusAndEvent(requestId, request.version(), next, event);
  return quote;
});
```

## 5. C：可用时间和排班

固定加锁顺序：所有受影响 staffId 升序 → 所有 requestId 升序 → 查询/验证 → 保存。同一事务内已经锁过的 ID 可以再次调用。

```java
return store.inTransaction(tx -> {
  tx.lockStaff(staffId);
  tx.lockRequest(requestId);
  var request = tx.findRequest(requestId).orElseThrow();
  // Check OFFICE_STAFF, expected version, active staff and accepted current quote.
  // Read tx.findAvailability(...): AVAILABLE must cover the whole interval;
  // UNAVAILABLE must not overlap it. These business checks are C's responsibility.
  var overlaps = tx.findOverlappingSchedules(staffId, pickupAt, deliveryAt);
  // For a new booking, reject a nonempty overlaps list.
  // When editing, exclude the current schedule's ID, not every record for the staff member.
  return tx.createSchedule(new ScheduleDraft(requestId, staffId, pickupAt, deliveryAt));
});
```

- 区间使用半开 [start,end)，相邻任务结束/开始时间相同不算重叠。业务上若需要通勤缓冲，由三人讨论后增加规则，不能偷偷改变接口含义。
- findOverlappingSchedules 只查已有承诺，不自动执行全部排班规则；数据库锁保证“检查+写入”可以串行执行。
- 更换人员时锁原人员和新人员，再锁申请。先读取的安排在加锁后必须重新读取、核对 version 和人员；如已变更，结束并重试，不能临时违反锁顺序。
- 修改可用时间也必须锁配送员，再检查受影响的配送，提示、阻止或重新安排的业务由 C 实现。
- createSchedule 要求已持有人员和申请锁；availability 的写入要求人员锁。没有锁会报 TRANSACTION_REQUIRED。
- 一个申请保留一个当前 schedule，重新安排更新它；历史配送事件不删除。若需要完整排班变更审计，先向 A 提出新增接口和表需求。

### 客户确认修改时间

- 创建 ScheduleChangeDraft 时保存 scheduleId、baseScheduleVersion 和提议时间。
- 提议 PENDING 时不修改正式安排。createScheduleChange 本身原子，但业务层仍需验证提议人和当前状态。
- 客户确认时，先验证本人；事务内锁人员和申请，再读安排、提议和版本；验证时段与冲突。
- **先调用 updateScheduleChangeDecision(CONFIRMED)，再调用 updateSchedule**；两步在同一个 inTransaction 内。确认操作检查 baseScheduleVersion，任何失败全部回滚。
- 客户拒绝只记录 DECLINED，不改变安排。提议已处理、重复确认等由 C 的业务层阻止。

## 6. C：配送执行与 B 的状态规则

```java
access.requireAssignedDelivery(requestId);
return store.inTransaction(tx -> {
  tx.lockRequest(requestId);
  access.requireAssignedDelivery(requestId); // Recheck after lock/reload.
  var request = tx.findRequest(requestId).orElseThrow();
  var event = new EventDraft(type, OffsetDateTime.now(clock), access.current().userId(), note);
  var next = lifecycle.nextStatus(request, tx.findEvents(requestId), event);
  return tx.saveStatusAndEvent(requestId, request.version(), next, event);
});
```

B 的 lifecycle 不访问数据库、不改变存储；只根据对象和历史验证并返回状态。C 检查实际分配、身份和操作场景。失败尝试可以保持原状态但追加事件。saveStatusAndEvent 原子保存，不代替合法转换或时间验证。

## 7. 固定网页路径与文件负责人

| 路径 | 允许角色 | 所有人 |
|---|---|---|
| /login、/logout、/api/session、/、/portal/* | 登录策略见 SecurityConfig | A |
| /requests/**、/quotes/**、/tracking/** | 客户、办公室员工；仍需记录检查 | B |
| /scheduling/** | 办公室员工 | C |
| /schedule-changes/** | 客户、办公室员工；仍需记录检查 | C |
| /availability/**、/delivery/** | 配送员、办公室员工；仍需本人/分配检查 | C |

C 的客户进度展示需求通过 B 的 /tracking 申请详情提供，双方约定显示字段，不各建一套记录。

- 当前 /workspace/* 是明确的接入说明页面，不是上述业务 Controller。
- 新路径先通知 A 更新安全配置和导航；默认未声明路径被拒绝。
- 所有 POST/PUT/DELETE 保留 CSRF。Thymeleaf 的 th:action POST 表单自动加入 token；JavaScript 请求须携带 token。不要关闭 CSRF 来绕过 403。
- 局部样式分开；公共 CSS 由 A 修改。当前 CSP 不允许内联脚本/样式，使用独立同源文件。

## 8. 错误和测试

NOT_FOUND → 404；INVALID_DATA → 400；DUPLICATE/STALE_DATA/CONFLICT → 409；内部存储/事务误用 → 500。WebErrors 只转换存储异常；B/C 按模块定义业务验证提示，不直接把 Throwable 或 SQL 返回给用户。

B/C 不用重新测试 JDBC 实现，但必须测试业务规则和组合事务：过期/拒绝不能排班、重复操作、时段冲突、客户不同意、非法配送转换、失败后重试和跨用户访问。每次提交都运行 ./mvnw verify。
