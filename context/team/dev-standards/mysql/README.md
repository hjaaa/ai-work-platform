# MySQL 开发规范（团队规范）

本目录内容以阿里巴巴《Java 开发手册（黄山版）》（[p3c](https://github.com/alibaba/p3c) 项目，2022-02 发布，当前最新版）第五章「MySQL 数据库」为源整理，并按本项目实际做了项目化裁剪（逻辑删除统一 `del_flag char(1)`、MyBatis-Plus 自动能力边界等）。文档中标注 **【项目调整】** 处以标注内容为准，与原书差异见 git 历史。

约束级别（【强制】/【推荐】/【参考】）说明见 [../README.md](../README.md)。

## 章节目录

| 文档 | 内容 |
|---|---|
| [01-table-schema.md](01-table-schema.md) | 建表规约 |
| [02-index.md](02-index.md) | 索引规约 |
| [03-sql.md](03-sql.md) | SQL 语句 |
| [04-orm.md](04-orm.md) | ORM 映射 |
