# 实施方案

## 变更清单

1. **SkillService / SkillServiceImpl**：`listSkills` 增加 `boolean includeSystem` 参数
2. **SkillController**：GET `/api/skills` 增加 `includeSystem` query param
3. **SkillServiceImplTest**：补充 includeSystem 测试
4. **skill.js**：`listSkills` 支持 `includeSystem` 参数
5. **WorkbenchView.vue**：`$` 触发技能浮层，选中插入 `$skillName `
