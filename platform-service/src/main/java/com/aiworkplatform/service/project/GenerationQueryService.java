package com.aiworkplatform.service.project;

import com.aiworkplatform.domain.entity.Deployment;
import com.aiworkplatform.domain.entity.Generation;

import java.util.List;

/**
 * 生成记录查询服务接口
 */
public interface GenerationQueryService {

    List<Generation> listByProjectId(String projectId);

    List<Deployment> listDeploymentsByProjectId(String projectId);
}
