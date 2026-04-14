package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 开发规范实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dev_spec")
public class DevSpec extends BaseEntity {

    /** 业务主键（全局唯一） */
    private String specId;

    /** 规范名称 */
    private String name;

    /** 规范类型：UI/FRONTEND/BACKEND */
    private String specType;

    /** 规范内容（Markdown 格式） */
    private String content;

    /** 创建人 */
    private String createdBy;
}
