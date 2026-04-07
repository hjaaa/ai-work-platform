## 2026-04-07 方案设计未对齐 Constitution 技术栈版本

- **现象**：AI 在 plan.md 中写了 Java 17+ / Spring Boot 3.x，但 constitution.md 明确要求 Java 21+ / Spring Boot 3.5.x
- **原因**：方案设计时未先读取 constitution.md，依赖通用常识填写版本号
- **正确做法**：方案设计前**必须**先加载 `.specify/memory/constitution.md`，检查"技术栈约束"章节，以项目约束为准而非通用常识
- **适用场景**：任何需求的方案设计阶段，尤其涉及技术选型、版本号、框架选择时
