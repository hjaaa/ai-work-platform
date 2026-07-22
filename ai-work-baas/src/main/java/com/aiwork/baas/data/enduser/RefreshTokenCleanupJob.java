package com.aiwork.baas.data.enduser;

import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.provision.SystemTableManifest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * grace 密文与过期 refresh token 定时清理(spec §7.6):
 * 只清超窗密文列、只删 expire_time 已过的行;已消费未过期行保留至原过期时间,
 * 保障 §7.2 超窗复用撤销会话的泄露检测。逐项目 best-effort,不取 DDL 锁。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Slf4j
@Component
public class RefreshTokenCleanupJob {

    private static final String CLEAR_EXPIRED_GRACE_CIPHERTEXT_SQL =
            "UPDATE `_refresh_tokens` SET replay_payload_ciphertext = NULL "
                    + "WHERE reuse_grace_until IS NOT NULL AND reuse_grace_until < NOW() "
                    + "AND replay_payload_ciphertext IS NOT NULL";

    /** 只删已过期行——已消费未过期行必须保留(spec v33 P0 修正)。 */
    private static final String DELETE_EXPIRED_TOKENS_SQL =
            "DELETE FROM `_refresh_tokens` WHERE expire_time < NOW()";

    private final BaasProjectMapper projectMapper;

    private final ProjectDataSourceRegistry registry;

    public RefreshTokenCleanupJob(BaasProjectMapper projectMapper, ProjectDataSourceRegistry registry) {
        this.projectMapper = projectMapper;
        this.registry = registry;
    }

    @Scheduled(initialDelayString = "${baas.auth.cleanup-initial-delay-millis:60000}",
            fixedDelayString = "${baas.auth.cleanup-interval-millis:300000}")
    public void scheduledCleanup() {
        cleanupOnce();
    }

    public void cleanupOnce() {
        List<BaasProject> projects = projectMapper.selectList(Wrappers.<BaasProject>lambdaQuery()
            .eq(BaasProject::getStatus, ProjectStatus.ACTIVE)
            .eq(BaasProject::getSystemTableVersion, SystemTableManifest.CURRENT_VERSION));
        for (BaasProject project : projects) {
            try {
                registry.execute(project, dataSource -> {
                    // §7.6/§13:单连接手动事务 + 统一 5 秒 queryTimeout(注册表本身未设,须在此层补)。
                    // 两条清理语句同一事务;超时或异常整体回滚,超大 _refresh_tokens 上的 DELETE 不会
                    // 长期占用行锁或阻塞调度线程(行锁竞争时 5 秒后被驱动 KILL,抛 SQLException 交由外层跳过)。
                    try (Connection connection = dataSource.getConnection()) {
                        boolean previousAutoCommit = connection.getAutoCommit();
                        connection.setAutoCommit(false);
                        try (Statement statement = connection.createStatement()) {
                            statement.setQueryTimeout(5);
                            statement.executeUpdate(CLEAR_EXPIRED_GRACE_CIPHERTEXT_SQL);
                            statement.executeUpdate(DELETE_EXPIRED_TOKENS_SQL);
                            connection.commit();
                        }
                        catch (SQLException exception) {
                            connection.rollback();
                            throw new IllegalStateException(exception);
                        }
                        finally {
                            try {
                                connection.setAutoCommit(previousAutoCommit);
                            }
                            catch (SQLException ignored) {
                                // 连接归还前恢复失败仅影响本连接,池会校验
                            }
                        }
                        return null;
                    }
                    catch (SQLException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
            catch (RuntimeException failure) {
                // 单项目失败不影响其余项目(§7.6)
                log.warn("refresh token cleanup failed projectId={} errorType={}", project.getId(),
                        failure.getClass().getSimpleName());
            }
        }
    }

}
