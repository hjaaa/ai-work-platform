package com.aiworkplatform.service.project;

import com.aiworkplatform.domain.entity.Deployment;
import com.aiworkplatform.domain.entity.Generation;
import com.aiworkplatform.domain.mapper.DeploymentMapper;
import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GenerationQueryService {

    private final GenerationMapper generationMapper;
    private final DeploymentMapper deploymentMapper;

    public GenerationQueryService(GenerationMapper generationMapper,
                                  DeploymentMapper deploymentMapper) {
        this.generationMapper = generationMapper;
        this.deploymentMapper = deploymentMapper;
    }

    public List<Generation> listByProjectId(String projectId) {
        return generationMapper.selectList(
                new LambdaQueryWrapper<Generation>()
                        .eq(Generation::getProjectId, projectId)
                        .orderByDesc(Generation::getCreatedAt));
    }

    public List<Deployment> listDeploymentsByProjectId(String projectId) {
        return deploymentMapper.selectList(
                new LambdaQueryWrapper<Deployment>()
                        .eq(Deployment::getProjectId, projectId)
                        .orderByDesc(Deployment::getCreatedAt));
    }
}
