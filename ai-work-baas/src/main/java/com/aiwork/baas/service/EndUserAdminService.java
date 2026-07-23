package com.aiwork.baas.service;

import com.aiwork.baas.controller.dto.EndUserVO;
import com.aiwork.baas.data.enduser.EndUserStore;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasAuditLog;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.exception.EndUserNotFoundException;
import com.aiwork.baas.mapper.BaasAuditLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Studio 终端用户管理(spec §7.3):软删/恢复在项目库事务内完成(含会话撤销),
 * 审计在事务提交之后 best-effort 写入——失败不回滚业务,仅记结构化 error 日志;
 * 禁止先审计后业务。执行前过 SystemTableVersionGate(ACTIVE + v3)。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Slf4j
@Service
public class EndUserAdminService {

    public record UserPage(long total, List<EndUserVO> records) {
    }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int MAX_PAGE_SIZE = 100;

    private final ProjectDataSourceRegistry registry;

    private final EndUserStore store;

    private final BaasAuditLogMapper auditLogMapper;

    private final SystemTableVersionGate versionGate;

    public EndUserAdminService(ProjectDataSourceRegistry registry, EndUserStore store,
            BaasAuditLogMapper auditLogMapper, SystemTableVersionGate versionGate) {
        this.registry = registry;
        this.store = store;
        this.auditLogMapper = auditLogMapper;
        this.versionGate = versionGate;
    }

    public UserPage list(BaasProject project, int page, int size) {
        versionGate.assertStudioReady(project);
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return inTransaction(project, connection -> {
            long total = store.countUsers(connection);
            // §10 分页规约第9条【强制】:页码超过总页数时返回最后一页,避免越界返回空列表。
            // 先按 total 求末页并钳制,再算 offset;用 long 参与运算,消除 page 取极大值时
            // (safePage-1)*safeSize int 相乘溢出为负 offset 导致 SQL 报错的风险。
            long totalPages = Math.max(1L, (total + safeSize - 1) / safeSize);
            int clampedPage = (int) Math.min(safePage, totalPages);
            int offset = (clampedPage - 1) * safeSize;
            List<EndUserVO> records = store.listUsers(connection, offset, safeSize)
                .stream()
                .map(user -> new EndUserVO(user.id(), user.email(), TIME_FORMAT.format(user.createTime()),
                        user.deletedAt() == null ? null : TIME_FORMAT.format(user.deletedAt())))
                .toList();
            return new UserPage(total, records);
        });
    }

    public void softDelete(BaasProject project, long userId, Long operatorUserId) {
        versionGate.assertStudioReady(project);
        boolean deleted = inTransaction(project, connection -> {
            EndUserStore.EndUserRow user = store.findUserById(connection, userId);
            if (user == null) {
                throw new EndUserNotFoundException();
            }
            if (user.deletedAt() != null) {
                // 已软删重复 DELETE:幂等成功,不重复审计
                return false;
            }
            store.softDeleteUser(connection, userId);
            // 同一项目库事务内按 §7.2 会话撤销语义撤销该用户全部会话
            store.revokeAllSessions(connection, userId);
            return true;
        });
        if (deleted) {
            auditBestEffort(project.getId(), operatorUserId, "END_USER_SOFT_DELETE", "userId=" + userId);
        }
    }

    public void restore(BaasProject project, long userId, Long operatorUserId) {
        versionGate.assertStudioReady(project);
        boolean restored = inTransaction(project, connection -> {
            EndUserStore.EndUserRow user = store.findUserById(connection, userId);
            if (user == null) {
                throw new EndUserNotFoundException();
            }
            if (user.deletedAt() == null) {
                return false;
            }
            // 恢复仅清 deleted_at;旧会话不复活(§7.3)
            store.restoreUser(connection, userId);
            return true;
        });
        if (restored) {
            auditBestEffort(project.getId(), operatorUserId, "END_USER_RESTORE", "userId=" + userId);
        }
    }

    /** 审计 best-effort(§7.3):业务事务提交之后调用;失败不回滚、不改变业务结果。 */
    private void auditBestEffort(Long projectId, Long operatorUserId, String action, String detail) {
        try {
            BaasAuditLog auditLog = new BaasAuditLog();
            auditLog.setProjectId(projectId);
            auditLog.setOperatorUserId(operatorUserId);
            auditLog.setAction(action);
            auditLog.setDetail(detail);
            auditLog.setLevel("INFO");
            auditLogMapper.insert(auditLog);
        }
        catch (Exception exception) {
            log.error("end user admin audit write failed projectId={} action={} detail={} errorType={}",
                    projectId, action, detail, exception.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface TxWork<T> {

        T run(Connection connection) throws SQLException;

    }

    private <T> T inTransaction(BaasProject project, TxWork<T> work) {
        return registry.execute(project, dataSource -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                }
                catch (Exception exception) {
                    try {
                        connection.rollback();
                    }
                    catch (SQLException rollbackFailure) {
                        exception.addSuppressed(rollbackFailure);
                    }
                    if (exception instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    SQLException sqlException = (SQLException) exception;
                    log.error("end user admin sql error, sqlState={}, vendorCode={}",
                            sqlException.getSQLState(), sqlException.getErrorCode());
                    throw new IllegalStateException("end user admin operation failed");
                }
                finally {
                    try {
                        connection.setAutoCommit(previousAutoCommit);
                    }
                    catch (SQLException ignored) {
                        // 连接归还前恢复失败仅影响本连接,池会校验
                    }
                }
            }
            catch (SQLException exception) {
                log.error("end user admin connection error, sqlState={}", exception.getSQLState());
                throw new IllegalStateException("end user admin operation failed");
            }
        });
    }

}
