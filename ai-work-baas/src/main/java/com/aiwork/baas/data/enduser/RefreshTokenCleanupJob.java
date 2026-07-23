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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * grace 密文与过期 refresh token 定时清理(spec §7.6):
 * 只清超窗密文列、只删 expire_time 已过的行;已消费未过期行保留至原过期时间,
 * 保障 §7.2 超窗复用撤销会话的泄露检测。逐项目 best-effort,不取 DDL 锁。
 *
 * <p>分批清理(spec §7.6/§13):每批以 LIMIT 界定行数、独立提交(autocommit)+ 5 秒 queryTimeout;
 * 单批超时/异常只影响该批,已提交批次的进度保留,保证超大 _refresh_tokens 也能有界推进,
 * 不会因两条语句同一大事务超时整体回滚而永久卡死、令过期行无限堆积。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Slf4j
@Component
public class RefreshTokenCleanupJob {

    /** 只清超窗 grace 密文——未超窗密文保留支撑泄露检测(spec §7.2)。 */
    private static final String CLEAR_EXPIRED_GRACE_CIPHERTEXT_SQL =
            "UPDATE `_refresh_tokens` SET replay_payload_ciphertext = NULL "
                    + "WHERE reuse_grace_until IS NOT NULL AND reuse_grace_until < NOW() "
                    + "AND replay_payload_ciphertext IS NOT NULL LIMIT ?";

    /** 只删已过期行——已消费未过期行必须保留(spec v33 P0 修正)。 */
    private static final String DELETE_EXPIRED_TOKENS_SQL =
            "DELETE FROM `_refresh_tokens` WHERE expire_time < NOW() LIMIT ?";

    /** auth 项目库操作统一 5 秒 queryTimeout(spec §7.6/§13);注册表未设,须在此层补。 */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    /** 单次运行单条语句的批数硬上限(防御性,防极端下无限循环);未清完的行下轮继续。 */
    private static final int MAX_BATCHES_PER_RUN = 10_000;

    private final BaasProjectMapper projectMapper;

    private final ProjectDataSourceRegistry registry;

    private final AuthProperties properties;

    public RefreshTokenCleanupJob(BaasProjectMapper projectMapper, ProjectDataSourceRegistry registry,
            AuthProperties properties) {
        this.projectMapper = projectMapper;
        this.registry = registry;
        this.properties = properties;
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
        int batchSize = Math.max(1, properties.getCleanupBatchSize());
        for (BaasProject project : projects) {
            try {
                registry.execute(project, dataSource -> {
                    try (Connection connection = dataSource.getConnection()) {
                        boolean previousAutoCommit = connection.getAutoCommit();
                        // 每批 executeUpdate 独立提交,已完成批次的进度不因后续批次超时而回滚
                        connection.setAutoCommit(true);
                        try {
                            cleanupInBatches(connection, CLEAR_EXPIRED_GRACE_CIPHERTEXT_SQL, batchSize);
                            cleanupInBatches(connection, DELETE_EXPIRED_TOKENS_SQL, batchSize);
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

    /** 分批执行带 LIMIT ? 的清理语句,直到某批影响行数 < batchSize(已清完)或达单次运行批数上限。 */
    private void cleanupInBatches(Connection connection, String sql, int batchSize) throws SQLException {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int affected;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setInt(1, batchSize);
                affected = statement.executeUpdate();
            }
            if (affected < batchSize) {
                return;
            }
        }
    }

}
