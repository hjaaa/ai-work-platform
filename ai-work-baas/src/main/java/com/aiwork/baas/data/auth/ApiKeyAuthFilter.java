package com.aiwork.baas.data.auth;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.error.DataErrorWriter;
import com.aiwork.baas.entity.BaasApiKey;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.entity.enums.KeyType;
import com.aiwork.baas.entity.enums.ProjectStatus;
import com.aiwork.baas.mapper.BaasApiKeyMapper;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.aiwork.baas.security.key.ApiKeyGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据面 API Key 鉴权(spec §7.4 顺序);鉴权结果进 request attribute,不进 SecurityContext(§7.5)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final BaasApiKeyMapper apiKeyMapper;

    private final BaasProjectMapper projectMapper;

    private final ApiKeyGenerator keyGenerator;

    private final BaasJwtVerifier jwtVerifier;

    private final DataErrorWriter errorWriter;

    private final DataPlaneProperties properties;

    private final ConcurrentHashMap<Long, Long> keyTouchMillis = new ConcurrentHashMap<>();

    public ApiKeyAuthFilter(BaasApiKeyMapper apiKeyMapper, BaasProjectMapper projectMapper,
            ApiKeyGenerator keyGenerator, BaasJwtVerifier jwtVerifier, DataErrorWriter errorWriter,
            DataPlaneProperties properties) {
        this.apiKeyMapper = apiKeyMapper;
        this.projectMapper = projectMapper;
        this.keyGenerator = keyGenerator;
        this.jwtVerifier = jwtVerifier;
        this.errorWriter = errorWriter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // ① 鉴权前按 Content-Length 预检(§13);流式兜底在 controller 读 body 时执行
            long contentLength = request.getContentLengthLong();
            if (contentLength > properties.getBodyMaxBytes()) {
                throw DataApiException
                    .payloadTooLarge("请求体超过 " + properties.getBodyMaxBytes() + " 字节上限");
            }

            String projectRef = extractProjectRef(request);
            String apiKeyPlaintext = request.getHeader("apikey");
            if (apiKeyPlaintext == null || apiKeyPlaintext.isBlank()) {
                throw DataApiException.unauthorized("缺少 apikey");
            }

            BaasApiKey apiKey = apiKeyMapper.selectOne(Wrappers.<BaasApiKey>lambdaQuery()
                .eq(BaasApiKey::getKeyHash, keyGenerator.sha256Hex(apiKeyPlaintext))
                .eq(BaasApiKey::getStatus, "ACTIVE"));
            if (apiKey == null || !keyGenerator.matches(apiKeyPlaintext, apiKey.getKeyHash())) {
                throw DataApiException.unauthorized("apikey 无效");
            }

            BaasProject project = projectMapper.selectById(apiKey.getProjectId());
            // ④ 三方一致性:apikey 解析项目 → 与 URL projectRef 比对;
            // 一致性完成前不触碰项目库(§7.4)
            if (project == null || !project.getProjectRef().equals(projectRef)) {
                throw DataApiException.unauthorized("apikey 与项目不匹配");
            }
            if (project.getStatus() != ProjectStatus.ACTIVE) {
                throw DataApiException.forbidden("项目当前不可用", null);
            }

            DataRequestContext context = resolveRole(request, project, apiKey);
            touchKeyBestEffort(apiKey);
            request.setAttribute(DataRequestContext.ATTRIBUTE, context);
            chain.doFilter(request, response);
        }
        catch (DataApiException exception) {
            errorWriter.write(response, exception);
        }
    }

    private DataRequestContext resolveRole(HttpServletRequest request, BaasProject project, BaasApiKey apiKey) {
        String authorization = request.getHeader("Authorization");
        boolean hasBearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        if (apiKey.getKeyType() == KeyType.SECRET) {
            if (isAuthPath(request)) {
                // spec §7.6:secret key 调用 auth 端点一律 403,不做身份混合
                throw DataApiException.forbidden("secret key 不得调用 auth 端点", null);
            }
            if (hasBearer) {
                // secret key 与终端用户 JWT 互斥(§7.4)
                throw DataApiException.unauthorized("secret key 不得同时携带终端用户 JWT");
            }
            return new DataRequestContext(project, DataRole.SERVICE_ROLE, null, null);
        }
        if (!hasBearer) {
            return new DataRequestContext(project, DataRole.ANON, null, null);
        }

        VerifiedEndUser user = jwtVerifier.verify(authorization.substring(7).trim(), project);
        return new DataRequestContext(project, DataRole.AUTHENTICATED, user.userId(), user.sessionId());
    }

    /** 服务内路径形如 /data/{ref}/auth/v1/…;第 3 段为 auth 即 auth 端点。 */
    private static boolean isAuthPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] segments = path.split("/");
        return segments.length >= 4 && "auth".equals(segments[3]);
    }

    private void touchKeyBestEffort(BaasApiKey apiKey) {
        long now = System.currentTimeMillis();
        long throttleMillis = properties.getKeyTouchThrottleSeconds() * 1000;
        Long last = keyTouchMillis.get(apiKey.getId());
        if (last != null && now - last < throttleMillis) {
            return;
        }
        if (last == null ? keyTouchMillis.putIfAbsent(apiKey.getId(), now) != null
                : !keyTouchMillis.replace(apiKey.getId(), last, now)) {
            return;
        }
        try {
            apiKeyMapper.update(null, Wrappers.<BaasApiKey>lambdaUpdate()
                .eq(BaasApiKey::getId, apiKey.getId())
                .set(BaasApiKey::getLastUsedTime, LocalDateTime.now()));
        }
        catch (Exception exception) {
            if (log.isDebugEnabled()) {
                log.debug("api key last_used_time update skipped", exception);
            }
        }
    }

    /**
     * 服务内路径形如 /data/{ref}/rest/v1/{table};取第二段。
     * @param request HTTP 请求
     * @return 项目 ref
     */
    private static String extractProjectRef(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] segments = path.split("/");
        // segments[0] 为空串,segments[1] = "data",segments[2] = ref
        if (segments.length < 3 || segments[2].isBlank()) {
            throw DataApiException.notFound("路径不存在");
        }
        return segments[2];
    }

}
