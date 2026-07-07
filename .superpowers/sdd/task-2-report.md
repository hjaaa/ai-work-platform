# Task 2 报告：钉钉绑定迁移到 `sys_user_social`

## 实现内容

1. 新增 `AbstractUserSocialHandler`，统一封装基于 `sys_user_social` 的 `info` / `bind` 逻辑：
   - `info(String identify)`：空标识直接返回 `null`；按 `type + identify` 查询绑定关系；命中后通过 `userId` 查询用户信息。
   - `bind(SysUser user, String identify)`：空标识抛绑定失败异常；若第三方账号已绑定其他用户则抛冲突异常；同用户已存在同类型绑定时更新 `identify`，否则插入新绑定。
2. 改造 `DingTalkLoginHandler` 继承 `AbstractUserSocialHandler`：
   - 保留钉钉 `identify()` 的开放平台取 `openId` 逻辑。
   - 使用 `SysUserSocialMapper` 替代 `sys_user.wx_ding_userid` 的读写。
   - 由 `bindFailed()` 提供钉钉绑定失败异常文案。
3. 调整 `SysUserMapper.xml#getUserVo`：
   - 新增 `query.userId` 查询条件，支撑绑定表命中后按用户 ID 取用户。
   - 删除旧的 `query.wxDingUserid` 查询分支，避免继续依赖回滚字段。
4. 新增通用错误文案：
   - `UpmsErrorCodes.SYS_SOCIAL_ALREADY_BOUND`
   - `messages_zh_CN.properties` / `messages_en.properties` 对应文案
5. 在 `SysUser.wxDingUserid` 注释中标记字段已废弃，仅保留回滚用途。
6. 按 brief 改写 `DingTalkLoginHandlerTest`，覆盖 8 个迁移后行为用例。

## 验证命令与结果

### RED

1. brief 原命令（按要求仅修正 `JAVA_HOME`）：

```bash
JAVA_HOME=$HOME/devsoft/jdk-21.0.10.jdk/Contents/Home mvn -T 4 -pl ai-work-upms/ai-work-upms-biz -am test -P 'cloud,!jdk18' -Dtest=DingTalkLoginHandlerTest
```

结果：失败，但失败点先被上游模块 surefire 拦截，未进入目标模块编译。
摘要：`ai-work-common-{core,excel,oss,xss}` 报 `No tests matching pattern "DingTalkLoginHandlerTest" were executed!`

2. 为获取目标测试 RED 证据，补充运行：

```bash
JAVA_HOME=$HOME/devsoft/jdk-21.0.10.jdk/Contents/Home mvn -T 4 -pl ai-work-upms/ai-work-upms-biz test -P 'cloud,!jdk18' -Dtest=DingTalkLoginHandlerTest -Dsurefire.failIfNoSpecifiedTests=false
```

结果：失败。
摘要：`DingTalkLoginHandlerTest` 编译失败，报 `找不到符号: 类 SysUserSocial`。这是在未联动重建依赖模块时的 RED 证据，说明新测试在旧实现状态下无法通过。

### GREEN

1. 目标测试验证：

```bash
JAVA_HOME=$HOME/devsoft/jdk-21.0.10.jdk/Contents/Home mvn -T 4 -pl ai-work-upms/ai-work-upms-biz -am test -P 'cloud,!jdk18' -Dtest=DingTalkLoginHandlerTest -Dsurefire.failIfNoSpecifiedTests=false
```

结果：通过。
摘要：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`

2. brief 验收命令：

```bash
JAVA_HOME=$HOME/devsoft/jdk-21.0.10.jdk/Contents/Home mvn -T 4 -pl ai-work-upms/ai-work-upms-biz -am test -P 'cloud,!jdk18'
```

结果：通过。
摘要：`BUILD SUCCESS`；`DingTalkLoginHandlerTest` 8 个用例全绿；依赖模块既有测试未回归。

## TDD Evidence

- RED 命令与失败输出摘要：
  - brief 原命令失败：上游模块 `No tests matching pattern "DingTalkLoginHandlerTest" were executed!`
  - 补充目标模块命令失败：`DingTalkLoginHandlerTest.java:[6,35] 找不到符号 类 SysUserSocial`
- GREEN 命令与通过输出摘要：
  - 目标测试命令通过：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`
  - brief 验收命令通过：`BUILD SUCCESS`

## 变更文件

- `ai-work-upms/ai-work-upms-biz/src/main/java/com/aiwork/admin/handler/AbstractUserSocialHandler.java`
- `ai-work-upms/ai-work-upms-biz/src/main/java/com/aiwork/admin/handler/DingTalkLoginHandler.java`
- `ai-work-upms/ai-work-upms-biz/src/test/java/com/aiwork/admin/handler/DingTalkLoginHandlerTest.java`
- `ai-work-upms/ai-work-upms-biz/src/main/resources/mapper/SysUserMapper.xml`
- `ai-work-upms/ai-work-upms-api/src/main/java/com/aiwork/admin/api/constant/UpmsErrorCodes.java`
- `ai-work-upms/ai-work-upms-api/src/main/resources/i18n/messages_zh_CN.properties`
- `ai-work-upms/ai-work-upms-api/src/main/resources/i18n/messages_en.properties`
- `ai-work-upms/ai-work-upms-api/src/main/java/com/aiwork/admin/api/entity/SysUser.java`
- `.superpowers/sdd/task-2-report.md`

## 自检结论

- 代码范围与 brief 一致：仅修改 brief 指定的 8 个业务文件，并新增要求的 `AbstractUserSocialHandler`。
- 没有引入新依赖。
- 中文 i18n 文案使用了 unicode 转义。
- 未回滚工作区其它改动；当前变更集中在钉钉绑定迁移与任务报告。

## 疑虑或风险

1. brief 给出的 RED 预期是“旧 handler 缺少三参构造器/可覆写异常方法”，但本地按 brief 原命令先被 reactor 上游 surefire 拦截，无法直接命中该失败点，因此报告中保留了原命令失败现象，并补充了目标模块的 RED 证据。
2. `wx_ding_userid` 字段仍保留在 `SysUser` / `UserVO` / SQL 列映射中，当前仅停止作为钉钉登录绑定路径的读写入口；这是 brief 要求的“保留回滚字段”状态。
