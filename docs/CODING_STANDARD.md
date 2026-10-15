# 编码规范

本项目采用 Google Java Style Guide 作为 Java 格式/命名参考，并使用以下约定：

- 类 PascalCase；方法和变量 lowerCamelCase；枚举 UPPER_SNAKE_CASE。
- 一个 public 顶层类/接口一个文件；按功能包组织；使用构造注入。
- SQL 和结果映射仅在 persistence；业务验证在业务模块；Controller 不执行 SQL。
- 用户输入使用 PreparedStatement/JdbcTemplate 参数绑定，不拼接 SQL 值。SQL 内部固定表名不是用户输入。
- 金额 BigDecimal；时间 OffsetDateTime；避免原始 Map 作为业务交换契约。
- 不记录密码、完整 principal、密码哈希或完整客户请求到日志。
- 用 StoreException.Code 传递稳定存储错误；用户提示不暴露底层 SQL。
- 多步更新使用明确事务；不要 catch 异常后继续返回成功。
- 资源由 JdbcTemplate/Spring 管理；测试验证异常、权限和数据一致性，不仅验证 getter/setter。
- 公共方法记录事务、锁和版本前提。共享契约变更由三人审查。

初始 Java 文件使用 google-java-format 统一格式。后续修改仅格式化所属文件，避免全仓格式化带来冲突。

参考：
- https://google.github.io/styleguide/javaguide.html
- https://docs.spring.io/spring-framework/reference/data-access/jdbc.html
- https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html
- https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
