package com.aiworkplatform.service.project.impl;

import com.aiworkplatform.domain.entity.Deployment;
import com.aiworkplatform.domain.entity.Generation;
import com.aiworkplatform.domain.mapper.DeploymentMapper;
import com.aiworkplatform.domain.mapper.GenerationMapper;
import com.aiworkplatform.service.project.GenerationQueryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GenerationQueryServiceImpl implements GenerationQueryService {

    private final GenerationMapper generationMapper;
    private final DeploymentMapper deploymentMapper;

    public GenerationQueryServiceImpl(GenerationMapper generationMapper,
                                      DeploymentMapper deploymentMapper) {
        this.generationMapper = generationMapper;
        this.deploymentMapper = deploymentMapper;
    }

    @Override
    public List<Generation> listByProjectId(String projectId) {
        return generationMapper.selectList(
                new LambdaQueryWrapper<Generation>()
                        .eq(Generation::getProjectId, projectId)
                        .orderByDesc(Generation::getCreatedAt));
    }

    @Override
    public List<Deployment> listDeploymentsByProjectId(String projectId) {
        return deploymentMapper.selectList(
                new LambdaQueryWrapper<Deployment>()
                        .eq(Deployment::getProjectId, projectId)
                        .orderByDesc(Deployment::getCreatedAt));
    }
}
