package com.aiwork.baas.data.enduser;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.mapper.BaasProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清理任务失败隔离(spec §7.6):单项目失败不阻断后续项目。
 */
class RefreshTokenCleanupJobTest {

    @Test
    void singleProjectFailureDoesNotBlockOthers() {
        BaasProjectMapper mapper = mock(BaasProjectMapper.class);
        ProjectDataSourceRegistry registry = mock(ProjectDataSourceRegistry.class);
        BaasProject failing = project(1L);
        BaasProject healthy = project(2L);
        when(mapper.selectList(any())).thenReturn(List.of(failing, healthy));
        when(registry.execute(eq(failing), any())).thenThrow(new IllegalStateException("boom"));
        when(registry.execute(eq(healthy), any())).thenReturn(null);

        new RefreshTokenCleanupJob(mapper, registry).cleanupOnce();

        // 第一个项目抛错被捕获后,第二个项目仍被处理
        verify(registry, times(1)).execute(eq(failing), any());
        verify(registry, times(1)).execute(eq(healthy), any());
    }

    private static BaasProject project(long id) {
        BaasProject project = new BaasProject();
        project.setId(id);
        return project;
    }

}
