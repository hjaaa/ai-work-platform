package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill")
public class Skill extends BaseEntity {

    /** 业务主键（全局唯一） */
    private String skillId;

    /** Skill 名称 */
    private String name;

    /** Skill 描述/内容（markdown 格式，同步写入磁盘文件） */
    private String description;

    /** 作用域：PERSONAL-个人级，PROJECT-项目级 */
    private String scope;

    /** 关联项目 ID（scope=PROJECT 时必填） */
    private String projectId;
}
