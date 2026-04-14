# $ 唤起技能列表

## 需求描述

在工作台对话页面的输入框中，用户输入 `$` 时弹出技能浮层列表（包含系统级技能），选中后将 `$skillName ` 插入输入框。

## 交互规则

- 触发：textarea 输入 `$` 字符
- 弹出位置：输入框上方浮层
- 列表内容：所有技能（PERSONAL + PROJECT + SYSTEM）
- 每行展示：技能名称（加粗）+ 描述摘要（灰色截断）
- 选中行为：将 `$skillName ` 插入输入框，关闭浮层
- 关闭：点击浮层外区域 / 按 ESC / 删除 `$` 字符
- 不需要输入过滤

## 技术方案

- 后端：`listSkills` 接口增加 `includeSystem` 参数
- 前端：WorkbenchView 监听 input 事件，检测 `$`，弹出浮层
- 后端已有 `SkillPromptConverter` 处理 `$skill-name` 格式
