# Lint 规则

> **设计原则**（来自 OpenAI Harness Engineering）：
> "由于这些 lint 是自定义的，我们编写错误信息时会在智能体情境中注入修复指令。"
> 每条规则不仅检查违规，还告诉 Agent **怎么修**。

## 规则来源

规则从三个渠道生长，不预设：

1. **Constitution 原则** → 可机械检查的部分直接编码为规则
2. **experience/ 高置信度条目** → 反复验证的经验升级为自动检查
3. **代码审查中反复出现的问题** → 人工提醒 2 次以上的模式编码为规则

## 规则表

每条规则包含：检查模式（grep/正则）、严重级别、修复指令（Agent 可直接执行）。

| ID | 规则名 | 严重级别 | 检查模式（grep） | 文件范围 | 修复指令 | 来源 |
|----|--------|----------|-----------------|----------|----------|------|
| L001 | 禁止 @Autowired | ERROR | `@Autowired` | `*.java` | 删除 @Autowired 注解，改为构造器注入：声明 private final 字段 + 构造器参数（或使用 @RequiredArgsConstructor）。参考 context/rules/pattern-rules.md §1 | Constitution II |
| L002 | 禁止 select * | ERROR | `select \*\|SELECT \*` | `*.xml` `*.java` | 将 select * 替换为显式列名。从对应 Entity/DO 类中找到所有字段名，逐个列出。 | Constitution 开发流程 |
| L003 | 禁止字符串拼接 SQL | ERROR | `" +.*".*[Ww]here\|".*" \+ ` | `*.java` | 将字符串拼接改为 MyBatis #{} 参数化或 JPA 命名参数 :paramName。绝对不要用 ${} 或字符串拼接。 | Constitution 开发流程 |
| L004 | 日志禁止打印敏感信息 | ERROR | `log\.\(info\|warn\|error\|debug\).*\(password\|token\|secret\|idCard\|bankCard\)` | `*.java` | 删除敏感字段的日志打印，或使用脱敏工具方法：手机号 138****1234，身份证 3201****1234。参考 context/team/logging-standards.md §脱敏规则 | Constitution V |
| L005 | 禁止吞异常 | WARN | `catch.*\{[[:space:]]*\}` | `*.java` | catch 块不能为空。必须：(1) 重新抛出业务异常，或 (2) 记录 WARN/ERROR 日志并返回明确错误。参考 context/team/coding-standards.md §异常处理 | Constitution 开发流程 |
| L006 | Controller 禁止直接访问 Mapper/Repository | WARN | `@Controller\|@RestController` + 同类中 `@Autowired.*Mapper\|@Autowired.*Repository\|private.*Mapper\|private.*Repository` | `*Controller.java` | Controller 不应直接依赖 Mapper/Repository。将数据访问逻辑移到 Service 层，Controller 只调用 Service。参考 context/team/coding-standards.md §分层职责 | Constitution I |
| L007 | Entity 禁止出现在 Controller 返回值 | WARN | `@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping` 所在方法返回类型包含 entity/do/model 包的类 | `*Controller.java` | Controller 返回值应使用 VO/DTO，不要直接返回 Entity。创建对应的 VO 类，通过 MapStruct 或手动转换。参考 context/team/coding-standards.md §对象分离 | Constitution I |
| L008 | 方法体超过 60 行 | WARN | 方法体行数 > 60（需脚本计算） | `*.java` | 将方法拆分为多个语义清晰的私有方法。每个方法只做一件事，方法名要能表达意图（read like prose）。 | Constitution VI |

## 检查级别说明

| 级别 | 含义 | 处理方式 |
|------|------|----------|
| **ERROR** | 违反安全/正确性不变量，必须修复 | Stop hook 检测到后输出警告，Agent 应立即修复 |
| **WARN** | 违反规范/最佳实践，强烈建议修复 | Stop hook 检测到后输出提醒，Agent 在当前任务完成后修复 |

## 新增规则流程

```
经验反复出现（置信度升为高）
    ↓
/optimize-flow 提醒："这条经验已被验证多次，建议升级为 lint 规则"
    ↓
用户确认 → 在本表中追加一行
    ↓
Stop hook 下次运行时自动检查新规则
```

## 与 experience/ 的关系

```
experience/（经验层）          lint-rules.md（规则层）
  置信度：低 → 中 → 高    ─→    升级为自动检查规则
  人工加载、按需提醒              机械执行、每次强制检查
```

lint 规则是 experience 的"毕业形态"——当一条经验被反复验证到不需要人工判断时，它就该变成自动化检查。
