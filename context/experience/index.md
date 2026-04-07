# 经验索引

> **Agent 必读文件**：每次需求分析或方案设计前，先加载本索引，按关键词匹配后按需加载对应的经验文件。
> 本文件应保持轻量（< 500 tokens），只存放关键词→文件的映射。

## 使用方式

1. Agent 识别用户意图中的关键词
2. 在下表中匹配相关经验文件
3. 按"加载时机"列判断当前是否需要加载
4. 置信度为"低"的条目仅供参考，"高"的条目必须遵守
5. 只加载匹配的文件，不全量加载

## 经验映射表

| 关键词 | 经验文件 | 加载时机 | 置信度 | 简述 |
|--------|----------|----------|--------|------|
| 方案设计, 技术选型, 版本号 | experience/方案设计流程/common-errors.md | 方案设计时 | 低 | 方案设计前必须先读 constitution.md 对齐技术栈版本 |
| MyBatis-Plus, 分页, Pagination | experience/MyBatisPlus/common-errors.md | 实施前 | 低 | MP 3.5.x 分页需额外引入 mybatis-plus-jsqlparser |
| Apache POI, Word, docx, 文档解析 | experience/ApachePOI/common-errors.md | 实施前 | 低 | POI 异常不全是 IOException，需 catch Exception |
| Maven, 多模块, spring-boot:run, 启动 | experience/Maven多模块/common-errors.md | 实施前 | 低 | 多模块项目 run 前必须先 install |
| 代码审查, Controller, Mapper, 分层 | experience/代码审查模式/common-errors.md | 实施前 | 低 | Controller 禁止直接注入 Mapper；进程执行提取工具类 |
| 代码审查, 自检, 实施完成 | experience/代码审查模式/self-check.md | 实施前 | 低 | 实施完成后自检：重复逻辑/死代码/递归限制/重复查询 |
| highlight.js, WebSocket, 前端单例 | experience/代码审查模式/common-errors.md | 实施前 | 低 | 前端库初始化提取单例；WebSocket 切换时先断开旧连接 |

<!-- 
示例条目：
| 商品发放, 奖品, 发奖 | experience/商品发放/common-errors.md | 需求分析时 | 高 | 钱包选择、库存锁定顺序 |
| Apollo, 配置中心 | experience/Apollo配置/field-specs.md | 方案设计时 | 中 | Apollo 配置格式和已知坑点 |
-->

## 置信度规则

| 级别 | 含义 | 升降条件 |
|------|------|----------|
| **低** | 首次记录，未经验证 | 新沉淀的经验默认为低 |
| **中** | 被验证过 1 次（第二次遇到同样问题时确认有效） | 低→中：再次遇到并确认有效 |
| **高** | 被验证过 2+ 次，或涉及资金/安全/数据一致性 | 中→高：第三次验证，或用户明确标记 |

- 置信度为"高"的经验可以考虑升级到 `context/team/` 或 `context/rules/` 成为正式规范
- 被证伪的经验：降级或标记删除，不要静默保留错误经验

## 沉淀规则

何时新增条目：
1. **AI 连续犯同一类错 2 次** → `experience/{场景}/common-errors.md`
2. **某类操作每次都要人工提醒** → `experience/{场景}/self-check.md`
3. **发现新的字段规范或格式要求** → `experience/{场景}/field-specs.md`
4. **尝试过但走不通的路** → `experience/{场景}/dead-ends.md`
