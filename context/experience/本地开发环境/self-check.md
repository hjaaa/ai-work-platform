# 本地开发环境 自检清单

## 开发类库版本不匹配时

当编译或运行报版本不匹配错误（如 JDK 版本、Maven 版本等）时：

- [ ] 先检查 `~/devsoft/` 目录下是否已有对应版本的开发类库
- [ ] 当前已有：JDK 11、JDK 21、Maven 3.9.11
- [ ] 用 `~/devsoft/` 下的版本替代系统默认版本，而非要求用户安装或降级
- [ ] 设置 `JAVA_HOME` 或 `MAVEN_HOME` 指向 `~/devsoft/` 对应目录

## 常用路径

| 工具 | 路径 |
|------|------|
| JDK 11 | `~/devsoft/jdk-11.0.29.jdk` |
| JDK 21 | `~/devsoft/jdk-21.0.10.jdk` |
| Maven 3.9.11 | `~/devsoft/apache-maven-3.9.11` |
