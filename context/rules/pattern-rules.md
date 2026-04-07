# 代码模式规则

> Agent 在生成代码时应遵循的模式约束。随踩坑经验积累逐步丰富。

## 已确立的模式

### 1. Service 构造模式

```java
@Service
@RequiredArgsConstructor  // 或手写构造器
public class XxxService {
    private final XxxRepository xxxRepository;
    private final YyyService yyyService;
    // 禁止 @Autowired
}
```

### 2. 统一响应封装模式

```java
// Controller 层统一返回
@GetMapping("/xxx")
public Result<XxxVO> getXxx(@Valid @RequestBody XxxDTO dto) {
    return Result.success(xxxService.getXxx(dto));
}
```

### 3. 幂等处理模式（优先级从高到低）

```
方案 1：数据库唯一约束 + INSERT IGNORE / ON DUPLICATE KEY UPDATE
方案 2：幂等表（Redis 或 DB），请求前检查 idempotentKey
方案 3：分布式锁（Redisson），仅在前两种不适用时考虑
```

### 4. 状态机校验模式

```java
// 显式校验 from → to，禁止直接 set
public void changeStatus(Order order, OrderStatus targetStatus) {
    if (!order.getStatus().canTransitTo(targetStatus)) {
        throw new BusinessException("非法状态流转: " + order.getStatus() + " -> " + targetStatus);
    }
    order.setStatus(targetStatus);
}
```

<!-- 
随着项目推进，新的模式会在这里积累。格式：
### N. 模式名
- 适用场景
- 代码示例
- 常见错误（如有）
-->
