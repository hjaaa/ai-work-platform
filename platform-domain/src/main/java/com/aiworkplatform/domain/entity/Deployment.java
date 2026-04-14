package com.aiworkplatform.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 部署记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("deployment")
public class Deployment extends BaseEntity {

    /** 项目标识 */
    private String projectId;

    /** Docker 镜像标签 */
    private String imageTag;

    /** 目标服务器 */
    private String targetServer;

    /** 状态: building/pushing/deploying/running/failed/rolled_back */
    private String status;

    /** 访问地址 */
    private String deployUrl;

    /** 错误信息 */
    private String errorMessage;
}
