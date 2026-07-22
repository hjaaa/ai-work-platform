package com.aiwork.baas.data.enduser;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目库系统表(_users/_sessions/_refresh_tokens)JDBC 访问(spec §7.6):
 * 全部方法在调用方事务连接上执行;不做任何动态 SQL 拼接。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Component
public class EndUserStore {

    public record EndUserRow(long id, String email, String passwordHash, LocalDateTime createTime,
            LocalDateTime deletedAt) {
    }

    public record RefreshTokenRow(long id, String tokenHash, long sessionId, LocalDateTime expireTime,
            LocalDateTime consumedAt, Long replacementTokenId, LocalDateTime reuseGraceUntil,
            String replayPayloadCiphertext, String sessionStatus, long userId) {
    }

    private static final String SELECT_USER_COLUMNS = "SELECT id, email, password_hash, create_time, deleted_at "
            + "FROM `_users` ";

    /** auth 项目库操作与数据面同口径 5 秒 queryTimeout(spec §7.6/§13)。 */
    private final int queryTimeoutSeconds;

    public EndUserStore(com.aiwork.baas.data.config.DataPlaneProperties properties) {
        this.queryTimeoutSeconds = properties.getQueryTimeoutSeconds();
    }

    private PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    private PreparedStatement prepareWithKeys(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        statement.setQueryTimeout(queryTimeoutSeconds);
        return statement;
    }

    public EndUserRow findUserByEmail(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = prepare(connection, SELECT_USER_COLUMNS + "WHERE email = ?")) {
            statement.setString(1, email);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readUser(resultSet) : null;
            }
        }
    }

    public EndUserRow findUserById(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = prepare(connection, SELECT_USER_COLUMNS + "WHERE id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readUser(resultSet) : null;
            }
        }
    }

    public long insertUser(Connection connection, String email, String passwordHash) throws SQLException {
        try (PreparedStatement statement = prepareWithKeys(connection,
                "INSERT INTO `_users` (email, password_hash) VALUES (?, ?)")) {
            statement.setString(1, email);
            statement.setString(2, passwordHash);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    public long insertSession(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = prepareWithKeys(connection,
                "INSERT INTO `_sessions` (user_id, status) VALUES (?, 'ACTIVE')")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    public long insertRefreshToken(Connection connection, long sessionId, String tokenHash,
            LocalDateTime expireTime) throws SQLException {
        try (PreparedStatement statement = prepareWithKeys(connection,
                "INSERT INTO `_refresh_tokens` (session_id, token_hash, expire_time) VALUES (?, ?, ?)")) {
            statement.setLong(1, sessionId);
            statement.setString(2, tokenHash);
            statement.setTimestamp(3, Timestamp.valueOf(expireTime));
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    public RefreshTokenRow lockRefreshToken(Connection connection, String tokenHash) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "SELECT rt.id, rt.token_hash, rt.session_id, rt.expire_time, rt.consumed_at, "
                        + "rt.replacement_token_id, rt.reuse_grace_until, rt.replay_payload_ciphertext, "
                        + "s.status, s.user_id FROM `_refresh_tokens` rt "
                        + "JOIN `_sessions` s ON s.id = rt.session_id WHERE rt.token_hash = ? FOR UPDATE")) {
            statement.setString(1, tokenHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                Timestamp consumedAt = resultSet.getTimestamp(5);
                long replacementTokenId = resultSet.getLong(6);
                boolean replacementNull = resultSet.wasNull();
                Timestamp graceUntil = resultSet.getTimestamp(7);
                return new RefreshTokenRow(resultSet.getLong(1), resultSet.getString(2), resultSet.getLong(3),
                        resultSet.getTimestamp(4).toLocalDateTime(),
                        consumedAt == null ? null : consumedAt.toLocalDateTime(),
                        replacementNull ? null : replacementTokenId,
                        graceUntil == null ? null : graceUntil.toLocalDateTime(), resultSet.getString(8),
                        resultSet.getString(9), resultSet.getLong(10));
            }
        }
    }

    public void consumeToken(Connection connection, long tokenId, long replacementTokenId,
            LocalDateTime reuseGraceUntil, String replayPayloadCiphertext) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_refresh_tokens` SET consumed_at = ?, replacement_token_id = ?, "
                        + "reuse_grace_until = ?, replay_payload_ciphertext = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            statement.setLong(2, replacementTokenId);
            statement.setTimestamp(3, Timestamp.valueOf(reuseGraceUntil));
            statement.setString(4, replayPayloadCiphertext);
            statement.setLong(5, tokenId);
            statement.executeUpdate();
        }
    }

    public void clearReplayCiphertext(Connection connection, long tokenId) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_refresh_tokens` SET replay_payload_ciphertext = NULL WHERE id = ?")) {
            statement.setLong(1, tokenId);
            statement.executeUpdate();
        }
    }

    public void revokeSession(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_sessions` SET status = 'REVOKED' WHERE id = ?")) {
            statement.setLong(1, sessionId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_refresh_tokens` SET consumed_at = ? WHERE session_id = ? AND consumed_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            statement.setLong(2, sessionId);
            statement.executeUpdate();
        }
    }

    public void revokeAllSessions(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_refresh_tokens` rt JOIN `_sessions` s ON s.id = rt.session_id "
                        + "SET rt.consumed_at = ? WHERE s.user_id = ? AND rt.consumed_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_sessions` SET status = 'REVOKED' WHERE user_id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, userId);
            statement.executeUpdate();
        }
    }

    public void touchSessionLastActive(Connection connection, long sessionId) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_sessions` SET last_active_time = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            statement.setLong(2, sessionId);
            statement.executeUpdate();
        }
    }

    public void updatePassword(Connection connection, long userId, String passwordHash) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_users` SET password_hash = ? WHERE id = ?")) {
            statement.setString(1, passwordHash);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    public int softDeleteUser(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_users` SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL")) {
            statement.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            statement.setLong(2, userId);
            return statement.executeUpdate();
        }
    }

    public int restoreUser(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                "UPDATE `_users` SET deleted_at = NULL WHERE id = ? AND deleted_at IS NOT NULL")) {
            statement.setLong(1, userId);
            return statement.executeUpdate();
        }
    }

    public List<EndUserRow> listUsers(Connection connection, int offset, int limit) throws SQLException {
        try (PreparedStatement statement = prepare(connection,
                SELECT_USER_COLUMNS + "ORDER BY id LIMIT ? OFFSET ?")) {
            statement.setInt(1, limit);
            statement.setInt(2, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<EndUserRow> users = new ArrayList<>();
                while (resultSet.next()) {
                    users.add(readUser(resultSet));
                }
                return users;
            }
        }
    }

    public long countUsers(Connection connection) throws SQLException {
        try (PreparedStatement statement = prepare(connection, "SELECT COUNT(*) FROM `_users`");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static EndUserRow readUser(ResultSet resultSet) throws SQLException {
        Timestamp deletedAt = resultSet.getTimestamp(5);
        return new EndUserRow(resultSet.getLong(1), resultSet.getString(2), resultSet.getString(3),
                resultSet.getTimestamp(4).toLocalDateTime(), deletedAt == null ? null : deletedAt.toLocalDateTime());
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("generated key missing");
            }
            return keys.getLong(1);
        }
    }

}
