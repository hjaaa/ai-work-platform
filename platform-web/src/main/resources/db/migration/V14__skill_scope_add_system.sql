-- scope 列增加 SYSTEM 枚举值说明
ALTER TABLE skill MODIFY COLUMN scope VARCHAR(20) NOT NULL COMMENT '作用域：PERSONAL-个人级，PROJECT-项目级，SYSTEM-系统级（不可修改/删除）';
