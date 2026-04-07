# 需求工作区

本目录是所有需求的**唯一工作区**，实现跨会话的"存档/读档"机制。

## 目录结构

```
requirements/
├── .template/         # 模板文件（新需求时复制）
│   ├── meta.yaml      # 元信息：阶段、关联服务
│   ├── process.txt    # 进度日志（做到哪了）
│   └── notes.md       # 过程笔记（待沉淀的经验）
└── {requirement-id}/  # 具体需求
    ├── meta.yaml      # 元信息
    ├── spec.md        # 需求规格
    ├── plan.md        # 实施计划
    ├── process.txt    # 进度日志
    └── notes.md       # 过程笔记
```

## 使用方式

- **新需求**：复制 `.template/` 目录为 `{requirement-id}/`，填写 meta.yaml
- **恢复会话**：Agent 读取 `meta.yaml`（阶段）+ `process.txt`（进度）+ `notes.md`（发现）
- **需求完成后**：执行 `/compound`，将 `notes.md` 中有价值的内容转移到 `context/experience/`

## 命名规范

requirement-id 建议使用简短有意义的名称：
- `user-auth` — 用户认证功能
- `payment-refund` — 退款功能
- `fix-order-timeout` — 修复订单超时 bug
