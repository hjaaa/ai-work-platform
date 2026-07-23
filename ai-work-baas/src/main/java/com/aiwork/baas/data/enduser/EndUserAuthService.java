package com.aiwork.baas.data.enduser;

import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.error.RateLimitedException;
import com.aiwork.baas.datasource.ProjectDataSourceRegistry;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.security.crypto.BaasCryptoService;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.aiwork.baas.service.SystemTableVersionGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTimeoutException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * 终端用户 Auth 业务(spec §7.2/§7.6):项目库单连接手动事务;
 * 限速前置于 bcrypt;响应 signup/login/refresh 同构。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Slf4j
@Component
public class EndUserAuthService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ProjectDataSourceRegistry registry;

    private final EndUserStore store;

    private final BCryptPasswordEncoder passwordEncoder;

    private final BaasJwtSigner jwtSigner;

    private final AuthRateLimiter rateLimiter;

    private final AuthProperties properties;

    private final BaasCryptoService cryptoService;

    private final ApiKeyGenerator keyGenerator;

    private final SystemTableVersionGate versionGate;

    private final ObjectMapper objectMapper;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 固定 dummy bcrypt hash(与 passwordEncoder 同强度):login 对不存在/软删用户比对它以对齐 bcrypt 时延。 */
    private final String dummyPasswordHash;

    public EndUserAuthService(ProjectDataSourceRegistry registry, EndUserStore store,
            BCryptPasswordEncoder passwordEncoder, BaasJwtSigner jwtSigner, AuthRateLimiter rateLimiter,
            AuthProperties properties, BaasCryptoService cryptoService, ApiKeyGenerator keyGenerator,
            SystemTableVersionGate versionGate, @Qualifier("dataPlaneObjectMapper") ObjectMapper objectMapper) {
        this.registry = registry;
        this.store = store;
        this.passwordEncoder = passwordEncoder;
        this.jwtSigner = jwtSigner;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.cryptoService = cryptoService;
        this.keyGenerator = keyGenerator;
        this.versionGate = versionGate;
        this.objectMapper = objectMapper;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer");
    }

    public ObjectNode signup(DataRequestContext ctx, JsonNode body, String clientIp) {
        versionGate.assertAuthReady(ctx.project());
        String email = normalizedEmail(body);
        String password = validatedPassword(body, "password");
        enforceSignupLimit(ctx.project().getId(), clientIp);
        String passwordHash = passwordEncoder.encode(password);
        return inTransaction(ctx.project(), connection -> {
            long userId;
            try {
                userId = store.insertUser(connection, email, passwordHash);
            }
            catch (SQLIntegrityConstraintViolationException exception) {
                // 含软删用户:邮箱唯一键不释放(§7.3)
                throw DataApiException.conflict("该邮箱已被注册");
            }
            return issueSession(ctx.project(), connection, store.findUserById(connection, userId));
        });
    }

    public ObjectNode login(DataRequestContext ctx, JsonNode body, String clientIp) {
        versionGate.assertAuthReady(ctx.project());
        String email = normalizedEmail(body);
        // login 仅校验字段存在/类型,不套用 8-72 字节创建期约束:
        // 否则短口令猜测会被 400 短路,既跳过限速计数,又让攻击者用状态码区分"太短"与"错误"(信息泄露)。
        String password = requiredPassword(body, "password");
        Long projectId = ctx.project().getId();
        String emailKey = AuthRateLimiter.emailKey("login", projectId, keyGenerator.sha256Hex(email));
        String ipKey = AuthRateLimiter.ipKey("login", projectId, clientIp);
        assertNotBlocked(projectId, emailKey, properties.getLoginEmailLimit());
        assertNotBlocked(projectId, ipKey, properties.getLoginIpLimit());
        return inTransaction(ctx.project(), connection -> {
            // 锁定读:与 softDelete/changePassword 的 _users 行 X 锁串行化,确保 issueSession 建的新会话
            // 不会逃逸并发撤销事务的 revokeAllSessions(§7.2/§7.3);锁定读见最新状态,deletedAt 复查即时生效。
            EndUserStore.EndUserRow user = store.findUserByEmailForUpdate(connection, email);
            // 取行锁后复查限速:并发同邮箱请求都通过前置 GET 检查后排队于此 FOR UPDATE,先到者失败已原子
            // INCR 计数;后到者在此即时命中 429,避免每个排队请求都执行昂贵 bcrypt、占满项目库连接池(§12.2)。
            assertNotBlocked(projectId, emailKey, properties.getLoginEmailLimit());
            assertNotBlocked(projectId, ipKey, properties.getLoginIpLimit());
            boolean eligible = user != null && user.deletedAt() == null;
            // 对不存在/软删邮箱也执行一次等价 bcrypt(比对固定 dummy hash),对齐响应时延、消除
            // 「已注册 active 邮箱」的时序侧信道防用户枚举(§7.2);无论结果均走统一失败计数与 401。
            boolean passwordOk = passwordEncoder.matches(password, eligible ? user.passwordHash() : dummyPasswordHash);
            if (!eligible || !passwordOk) {
                countCredentialFailure(projectId, emailKey, ipKey);
                // 统一文案:不泄露邮箱注册状态/软删状态(§7.2)
                throw DataApiException.unauthorized("邮箱或密码错误");
            }
            rateLimiter.clear(emailKey);
            return issueSession(ctx.project(), connection, user);
        });
    }

    /** 四分支结果:③ 需要「先提交撤销、再 401」,不能靠抛异常回滚,故以结果对象承载。 */
    private record RefreshOutcome(String responseJson, boolean leakedReuse) {
    }

    public ObjectNode refresh(DataRequestContext ctx, JsonNode body) {
        versionGate.assertAuthReady(ctx.project());
        JsonNode tokenNode = body == null ? null : body.get("refresh_token");
        if (tokenNode == null || !tokenNode.isTextual() || tokenNode.textValue().isBlank()) {
            throw DataApiException.badRequest("refresh_token 缺失或类型错误");
        }
        String tokenHash = keyGenerator.sha256Hex(tokenNode.textValue());
        Long projectId = ctx.project().getId();
        RefreshOutcome outcome = inTransaction(ctx.project(), connection -> {
            EndUserStore.RefreshTokenRow row = store.lockRefreshToken(connection, tokenHash);
            LocalDateTime now = LocalDateTime.now();
            if (row == null || row.expireTime().isBefore(now) || !"ACTIVE".equals(row.sessionStatus())) {
                // 分支④:过期/不存在/会话非 ACTIVE
                throw DataApiException.unauthorized("刷新令牌无效");
            }
            if (row.consumedAt() != null) {
                if (row.reuseGraceUntil() != null && !now.isAfter(row.reuseGraceUntil())) {
                    // 分支②:grace 内重放,解密返回同一响应(幂等)
                    if (row.replayPayloadCiphertext() == null) {
                        throw DataApiException.unauthorized("刷新令牌无效");
                    }
                    return new RefreshOutcome(cryptoService.decrypt(row.replayPayloadCiphertext(),
                            replayAad(projectId, row.sessionId(), row.id())), false);
                }
                // 分支③:超窗重放判定泄露——撤销整个会话并提交,随后 401;顺带惰性清密文(§7.6)
                store.clearReplayCiphertext(connection, row.id());
                store.revokeSession(connection, row.sessionId());
                return new RefreshOutcome(null, true);
            }
            // 分支①:行锁事务内轮换
            EndUserStore.EndUserRow user = store.findUserById(connection, row.userId());
            // 软删用户禁止刷新(§7.3):与 currentUser/changePassword 的 deletedAt 复查同口径,
            // 兜住任何逃逸会话撤销的 ACTIVE 会话,不再为软删账户续签 access token。
            if (user == null || user.deletedAt() != null) {
                throw DataApiException.unauthorized("刷新令牌无效");
            }
            String newRefreshPlaintext = generateRefreshToken();
            long childTokenId = store.insertRefreshToken(connection, row.sessionId(),
                    keyGenerator.sha256Hex(newRefreshPlaintext),
                    now.plusDays(properties.getRefreshTtlDays()));
            BaasJwtSigner.SignedAccessToken access = jwtSigner.sign(ctx.project(), user.id(), row.sessionId());
            ObjectNode response = authResponse(user, access, newRefreshPlaintext);
            String responseJson = response.toString();
            store.consumeToken(connection, row.id(), childTokenId,
                    now.plusSeconds(properties.getReuseGraceSeconds()),
                    cryptoService.encrypt(responseJson, replayAad(projectId, row.sessionId(), row.id())));
            store.touchSessionLastActive(connection, row.sessionId());
            return new RefreshOutcome(responseJson, false);
        });
        if (outcome.leakedReuse()) {
            // 撤销已提交,再返回 401(§7.2 超窗重放判定为泄露)
            throw DataApiException.unauthorized("刷新令牌已失效,会话已撤销");
        }
        try {
            return (ObjectNode) objectMapper.readTree(outcome.responseJson());
        }
        catch (java.io.IOException exception) {
            throw DataApiException.internal("刷新响应解析失败");
        }
    }

    private static String replayAad(Long projectId, long sessionId, long tokenId) {
        // AAD 绑定 project + session + token,防密文跨记录替换(§7.2)
        return projectId + ":refresh_replay:" + sessionId + ":" + tokenId;
    }

    public void logout(DataRequestContext ctx) {
        versionGate.assertAuthReady(ctx.project());
        inTransaction(ctx.project(), connection -> {
            // §7.6:仅撤销 JWT 所指会话;会话已撤销时重复调用仍成功(幂等)
            store.revokeSession(connection, ctx.sessionId());
            return null;
        });
    }

    public ObjectNode currentUser(DataRequestContext ctx) {
        versionGate.assertAuthReady(ctx.project());
        return inTransaction(ctx.project(), connection -> {
            EndUserStore.EndUserRow user = store.findUserById(connection, ctx.endUserId());
            // 软删用户即便持 TTL 内有效 JWT,也不再允许账户管理类操作(§7.3):返回 401。
            // 与「数据面 /rest 不回查 _users、旧 JWT 仍可读业务数据」不冲突——那是 §7.5 的数据面口径,
            // 此处是账户管理端点,必须回查软删状态。
            if (user == null || user.deletedAt() != null) {
                throw DataApiException.unauthorized("用户不存在");
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", user.id());
            node.put("email", user.email());
            node.put("createTime", TIME_FORMAT.format(user.createTime()));
            return node;
        });
    }

    public void changePassword(DataRequestContext ctx, JsonNode body, String clientIp) {
        versionGate.assertAuthReady(ctx.project());
        // currentPassword 与 newPassword 一致走 8–72 字节校验(§7.2:统一密码输入约束,避免 bcrypt 截断歧义):
        // 合法存量密码必在 8–72 字节内,超界的 currentPassword 不可能匹配,提前 400 拒绝而非落入 401
        String currentPassword = validatedPassword(body, "currentPassword");
        String newPassword = validatedPassword(body, "newPassword");
        Long projectId = ctx.project().getId();
        inTransaction(ctx.project(), connection -> {
            // 锁定读:串行化并发改密,消除两个请求都读旧哈希、各自校验通过、后写覆盖前写的丢失更新;
            // 同时与 login/softDelete 的 _users 行锁串行化(§7.2)。
            EndUserStore.EndUserRow user = store.findUserByIdForUpdate(connection, ctx.endUserId());
            // 软删用户禁止改密(§7.3):持 TTL 内有效 JWT 也返回 401,不进入 bcrypt/限速
            if (user == null || user.deletedAt() != null) {
                throw DataApiException.unauthorized("用户不存在");
            }
            String emailKey = AuthRateLimiter.emailKey("login", projectId, keyGenerator.sha256Hex(user.email()));
            String ipKey = AuthRateLimiter.ipKey("login", projectId, clientIp);
            // 限速检查前置于 bcrypt(§12.2:currentPassword 错误与 login 失败共用维度)
            assertNotBlocked(projectId, emailKey, properties.getLoginEmailLimit());
            assertNotBlocked(projectId, ipKey, properties.getLoginIpLimit());
            if (!passwordEncoder.matches(currentPassword, user.passwordHash())) {
                countCredentialFailure(projectId, emailKey, ipKey);
                // access JWT 可能被窃:旧密码校验阻止窃取者借改密永久接管账户(§7.2)
                throw DataApiException.unauthorized("当前密码错误");
            }
            store.updatePassword(connection, user.id(), passwordEncoder.encode(newPassword));
            // 成功后撤销该用户全部会话(含当前,§7.2)
            store.revokeAllSessions(connection, user.id());
            return null;
        });
    }

    // ===== 共享基建(Task 9/10 复用) =====

    @FunctionalInterface
    interface TxWork<T> {

        T run(Connection connection) throws SQLException;

    }

    <T> T inTransaction(BaasProject project, TxWork<T> work) {
        return registry.execute(project, dataSource -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean previousAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = work.run(connection);
                    connection.commit();
                    return result;
                }
                catch (Error error) {
                    rollback(connection, error);
                    throw error;
                }
                catch (Exception exception) {
                    rollback(connection, exception);
                    throw asDataException(exception);
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
                throw asDataException(exception);
            }
        });
    }

    ObjectNode issueSession(BaasProject project, Connection connection, EndUserStore.EndUserRow user)
            throws SQLException {
        long sessionId = store.insertSession(connection, user.id());
        String refreshPlaintext = generateRefreshToken();
        store.insertRefreshToken(connection, sessionId, keyGenerator.sha256Hex(refreshPlaintext),
                LocalDateTime.now().plusDays(properties.getRefreshTtlDays()));
        BaasJwtSigner.SignedAccessToken access = jwtSigner.sign(project, user.id(), sessionId);
        return authResponse(user, access, refreshPlaintext);
    }

    ObjectNode authResponse(EndUserStore.EndUserRow user, BaasJwtSigner.SignedAccessToken access,
            String refreshPlaintext) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("access_token", access.token());
        root.put("token_type", "bearer");
        root.put("expires_in", access.expiresInSeconds());
        root.put("refresh_token", refreshPlaintext);
        ObjectNode userNode = root.putObject("user");
        userNode.put("id", user.id());
        userNode.put("email", user.email());
        userNode.put("createTime", TIME_FORMAT.format(user.createTime()));
        return root;
    }

    String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "rt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    void countCredentialFailure(Long projectId, String emailKey, String ipKey) {
        // 失败自增两个维度;直接用原子 INCR 返回值即时判定 429,不依赖下次请求的前置 GET 检查。
        // 前置 assertNotBlocked 只是优化;真正的硬闸在这里,消除并发下 check-then-act 的穿透
        //(N 个并发失败同时通过前置检查、全部返回 401 而无人触发 429)。
        AuthRateLimiter.RateProbe emailProbe = rateLimiter.increment(projectId, emailKey,
                properties.getLoginEmailWindowSeconds());
        AuthRateLimiter.RateProbe ipProbe = rateLimiter.increment(projectId, ipKey,
                properties.getLoginIpWindowSeconds());
        throwIfOverLimit(emailProbe, properties.getLoginEmailLimit());
        throwIfOverLimit(ipProbe, properties.getLoginIpLimit());
    }

    /** 基于原子 INCR 返回值硬闸:count 超过 limit 即抛 429(与 signup 的 enforceSignupLimit 同口径)。 */
    private void throwIfOverLimit(AuthRateLimiter.RateProbe probe, long limit) {
        // Redis 故障 probe=null → fail-open,不阻断
        if (probe != null && probe.count() > limit) {
            throw new RateLimitedException("尝试次数过多,请稍后重试", Math.max(1, probe.ttlSeconds()));
        }
    }

    void assertNotBlocked(Long projectId, String key, long limit) {
        Long retryAfter = rateLimiter.retryAfterIfBlocked(projectId, key, limit);
        if (retryAfter != null) {
            throw new RateLimitedException("尝试次数过多,请稍后重试", retryAfter);
        }
    }

    private void enforceSignupLimit(Long projectId, String clientIp) {
        String key = AuthRateLimiter.ipKey("signup", projectId, clientIp);
        AuthRateLimiter.RateProbe probe = rateLimiter.increment(projectId, key,
                properties.getSignupIpWindowSeconds());
        // 无论成败计数(§12.2);Redis 故障 probe=null → fail-open 放行
        if (probe != null && probe.count() > properties.getSignupIpLimit()) {
            throw new RateLimitedException("注册过于频繁,请稍后重试", Math.max(1, probe.ttlSeconds()));
        }
    }

    static String normalizedEmail(JsonNode body) {
        JsonNode emailNode = body == null ? null : body.get("email");
        if (emailNode == null || !emailNode.isTextual()) {
            throw DataApiException.badRequest("email 缺失或类型错误");
        }
        String email = emailNode.textValue().trim().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > 255 || !email.contains("@")) {
            throw DataApiException.badRequest("email 格式非法");
        }
        return email;
    }

    /** 登录态密码取值:仅要求字段存在且为字符串,不校验长度(校验见 {@link #validatedPassword})。 */
    static String requiredPassword(JsonNode body, String field) {
        JsonNode passwordNode = body == null ? null : body.get(field);
        if (passwordNode == null || !passwordNode.isTextual()) {
            throw DataApiException.badRequest(field + " 缺失或类型错误");
        }
        return passwordNode.textValue();
    }

    static String validatedPassword(JsonNode body, String field) {
        JsonNode passwordNode = body == null ? null : body.get(field);
        if (passwordNode == null || !passwordNode.isTextual()) {
            throw DataApiException.badRequest(field + " 缺失或类型错误");
        }
        String password = passwordNode.textValue();
        int byteLength = password.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 8 || byteLength > 72) {
            // bcrypt 只取前 72 字节,超长直接拒绝避免截断歧义(§7.2)
            throw DataApiException.badRequest(field + " 长度须为 8-72 字节");
        }
        return password;
    }

    private static void rollback(Connection connection, Throwable primaryFailure) {
        try {
            connection.rollback();
        }
        catch (SQLException rollbackFailure) {
            primaryFailure.addSuppressed(rollbackFailure);
        }
    }

    private static RuntimeException asDataException(Exception exception) {
        if (exception instanceof DataApiException dataApiException) {
            return dataApiException;
        }
        if (exception instanceof SQLTimeoutException) {
            return DataApiException.internal("SQL 执行超时");
        }
        if (exception instanceof SQLException sqlException) {
            // 脱敏:只落 sqlState/vendorCode(§11)
            log.error("auth sql error, sqlState={}, vendorCode={}", sqlException.getSQLState(),
                    sqlException.getErrorCode());
            return DataApiException.internal("操作失败");
        }
        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return DataApiException.internal("操作失败");
    }

}
