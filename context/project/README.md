# 项目级知识

本目录存放特定项目/服务的知识，按服务名组织。

## 目录结构

```
project/
└── {service-name}/
    ├── architecture.md    # 架构设计（模块划分、技术选型依据）
    ├── domain-model.md    # 领域模型（核心实体、关系、状态机）
    └── known-issues.md    # 已知问题（当前存在的技术债/限制）
```

## 何时创建

- 项目初始化时：创建 `architecture.md`，记录初始架构决策
- 领域模型稳定后：创建 `domain-model.md`
- 发现技术限制时：记录到 `known-issues.md`

> 不要预创建空文件。只在有真实内容时才创建。
