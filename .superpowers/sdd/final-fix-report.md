# Final Branch Review Fix Report

## 结果

- 状态：DONE
- 基线：`c600be6666b630186d96bd21b280467aa6d1791e`
- 范围：仅处理 final-fix brief 确认的 3 个 Important，未修改计划文档，未扩展到 PROVISIONING lease/reconciler 或 client_credentials principal。

## 1. ProjectProvisioner 系统表 schema

- `_users.id`、`_sessions.id/user_id`、`_refresh_tokens.id/session_id/replacement_token_id` 统一为 `bigint unsigned`。
- `_sessions`、`_refresh_tokens` 补齐 `update_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，三张系统表均包含 `id/create_time/update_time`。
- RED：MySQL 8.4 Testcontainers 查询 `information_schema.columns` 时，6 个目标 ID 列实际均返回 `bigint`，与预期 `bigint unsigned` 不符。
- GREEN：`ProjectProvisionerTest` 4/4 通过，精确校验 6 个 ID 列类型以及三张表的公共字段。

## 2. Registry 关闭失败硬许可

- 物理关闭成功后才进入 `CLOSED` 并释放 `poolCapacity`、`globalBudget`；关闭前抛错时保持 `DRAINING` 和 ref 隔离。
- 增加关闭中的条目登记与明确 `retryClose` 入口；成功关闭只释放一次许可，失败重试不会重复释放。
- 增加 `@PreDestroy closeAll()`，在 `capacityLock` 内原子关闭 Registry 生命周期，同时关闭 `pools` 与 `closingEntries`；每个仍隔离的池最多尝试 3 次，且不依赖 sleep。
- `execute` 快速检查关闭状态，`createIfAbsent` 在锁内和建池前二次检查；关闭线性化点前已借用的请求按在途请求排空，关闭开始后不再创建或借用池。
- RED：关闭前故障时旧实现错误释放 maxPools/全局预算；LRU 关闭失败的 ref 可被立即重建；`closeAll` 与明确重试入口尚不存在。
- 生命周期竞态 RED：`closeAll` 持有建池锁并关闭快照池时，新 ref 的首次借用仍可在其返回后调用 factory；进一步让新 ref 先通过快速检查再阻塞于建池锁，旧中间实现仍多创建 1 个池（factory 实际 2 次、预期 1 次）。
- GREEN：`ProjectDataSourceRegistryTest` 18/18 通过，覆盖容量、预算、LRU ref 隔离、成功重试恰好释放一次、容器销毁关闭全部注册池，以及关闭开始后的快速拒绝和锁内二次拒绝。

## 3. Studio 错误响应

- 使用仅作用于 `com.aiwork.baas.controller` 的模块局部 Advice，将项目不存在、参数校验/绑定、路径类型、非法 JSON/枚举、非法参数、生命周期/Key 状态冲突和兜底异常统一转换为 `R<Void>`。
- HTTP 状态分别保持 404、400、409、500；用户文案为简体中文，500 响应不回显异常消息或敏感内容。
- RED：MockMvc 错误路径中，原实现出现 Spring 默认空响应、异常直抛和英文 404；9 个错误路径测试为 5 个断言失败、4 个异常。
- GREEN：`StudioProjectControllerTest` 17/17 通过，包含原有成功路径与新增错误契约断言。

## 验证

- 聚焦测试：`mvn -pl ai-work-baas -am test -Dtest='ProjectProvisionerTest,ProjectDataSourceRegistryTest,StudioProjectControllerTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.compiler.source=17 -Dmaven.compiler.target=17 -Dmaven.compiler.release=17`
  - 39 tests，0 failures，0 errors，0 skipped。
- 全量测试：`mvn -q -pl ai-work-baas -am test -Dmaven.compiler.source=17 -Dmaven.compiler.target=17 -Dmaven.compiler.release=17`
  - JDK 21、MySQL 8.4 Testcontainers，退出码 0。
  - Surefire 汇总：10 个测试类，89 tests，0 failures，0 errors，0 skipped。
- `git diff --check`：通过。

## 残留风险

- 若物理池连续 3 次关闭仍失败，`closeAll()` 会记录告警并继续保留隔离条目及硬许可，避免错误复用；后续仍可通过明确重试入口再次关闭。该行为优先保证容量账本正确，不包含超出本次范围的后台重试调度。
