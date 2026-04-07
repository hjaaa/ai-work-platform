# 团队编码规范

> 本文件从 Constitution 提炼，供 Agent 在编码阶段自动加载。
> 权威来源：`.specify/memory/constitution.md`，本文件与其冲突时以 Constitution 为准。

## 分层职责

| 层 | 职责 | 禁止 |
|----|------|------|
| Controller | 参数校验、鉴权、调用 Service、封装响应 | 业务判断、数据转换、直接访问 Mapper |
| Service | 业务编排、事务管理 | 操作 HTTP 请求/响应对象、循环依赖 |
| Repository/Mapper | 数据访问 | 嵌入业务逻辑（条件分支、金额计算） |

## 对象分离

- DTO（接口入参/出参）、VO（视图对象）、DO/Entity（持久化对象）严格分离
- 跨层传输通过显式转换（手写或 MapStruct），禁止透传 Entity 到 Controller

## 依赖注入

- 构造器注入，`private final` 字段
- 禁止 `@Autowired`（包括构造器上的冗余标注）
- 构造器参数 > 7 个 → 审视职责划分

## 方法与类

- 方法体 ≤ 60 行
- 一个 Util/Helper 至少被 3 处引用才有存在价值
- 禁止预设"未来可能需要"的扩展点

## 异常处理

- 禁止吞异常，catch 后必须：重新抛出业务异常 / 记录日志并返回明确错误
- 统一 BusinessException 体系
- 对外接口禁止暴露堆栈

## SQL 规范

- 禁止 `select *`
- 禁止字符串拼接 SQL（参数化）
- 大表分页禁止深 offset（使用游标/ID 方案）

## 金额与状态

- 金额：BigDecimal，明确 scale + RoundingMode
- 状态流转：显式校验 from → to，禁止直接 set
- 幂等：唯一约束 > 幂等表/幂等 key > 分布式锁
