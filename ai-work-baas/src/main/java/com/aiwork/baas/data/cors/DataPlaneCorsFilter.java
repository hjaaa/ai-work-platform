package com.aiwork.baas.data.cors;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.entity.BaasProject;
import com.aiwork.baas.mapper.BaasProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据面 CORS(spec §12.2):在 ApiKeyAuthFilter 之前,仅查平台元数据,不创建任何项目库连接;
 * per-project allowed_origins(NULL = *,* 时 allowCredentials=false)。
 *
 * @author ai-work
 * @date 2026/07/21
 */
@Slf4j
public class DataPlaneCorsFilter extends OncePerRequestFilter {

    private static final String ALLOW_METHODS = "GET, POST, PATCH, DELETE, OPTIONS";

    private static final String ALLOW_HEADERS = "apikey, Authorization, Content-Type, Prefer";

    private static final String EXPOSE_HEADERS = "Content-Range";

    private final BaasProjectMapper projectMapper;

    private final ObjectMapper objectMapper;

    private final DataPlaneProperties properties;

    public DataPlaneCorsFilter(BaasProjectMapper projectMapper, ObjectMapper objectMapper,
            DataPlaneProperties properties) {
        this.projectMapper = projectMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean preflight = "OPTIONS".equals(request.getMethod())
                && request.getHeader("Access-Control-Request-Method") != null;
        String origin = request.getHeader("Origin");
        // 实际响应与预检均设置 Vary(§12.2:防 per-project Origin 回显被中间缓存跨项目复用)
        response.addHeader("Vary", "Origin");
        if (preflight) {
            response.addHeader("Vary", "Access-Control-Request-Method");
            response.addHeader("Vary", "Access-Control-Request-Headers");
        }
        if (origin != null) {
            applyCorsHeaders(request, response, origin, preflight);
        }
        if (preflight) {
            // 预检不携带 apikey,在此终止,不进鉴权;
            // projectRef 不存在时同样 204、无 CORS 头,不泄露存在性
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        chain.doFilter(request, response);
    }

    private void applyCorsHeaders(HttpServletRequest request, HttpServletResponse response, String origin,
            boolean preflight) {
        BaasProject project = findProject(request);
        if (project == null) {
            return;
        }
        AllowedOrigins allowedOrigins = parseAllowedOrigins(project);
        if (!allowedOrigins.wildcard() && !allowedOrigins.values().contains(origin)) {
            return;
        }
        // 默认 * 时固定 allowCredentials=false(§12.2):不输出 Allow-Credentials 头
        response.setHeader("Access-Control-Allow-Origin", allowedOrigins.wildcard() ? "*" : origin);
        response.setHeader("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        if (preflight) {
            response.setHeader("Access-Control-Allow-Methods", ALLOW_METHODS);
            response.setHeader("Access-Control-Allow-Headers", ALLOW_HEADERS);
            response.setHeader("Access-Control-Max-Age", String.valueOf(properties.getCorsMaxAgeSeconds()));
        }
    }

    private BaasProject findProject(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String[] segments = path.split("/");
        if (segments.length < 3 || segments[2].isBlank()) {
            return null;
        }
        return projectMapper
            .selectOne(Wrappers.<BaasProject>lambdaQuery().eq(BaasProject::getProjectRef, segments[2]));
    }

    /** NULL 才表示通配;空白、畸形 JSON、非字符串数组全部 fail-closed。 */
    private AllowedOrigins parseAllowedOrigins(BaasProject project) {
        String allowedOriginsJson = project.getAllowedOrigins();
        if (allowedOriginsJson == null) {
            return new AllowedOrigins(true, List.of());
        }
        if (allowedOriginsJson.isBlank()) {
            log.warn("allowed_origins is blank, CORS denied, projectId={}", project.getId());
            return new AllowedOrigins(false, List.of());
        }
        try {
            JsonNode node = objectMapper.readTree(allowedOriginsJson);
            if (!node.isArray()) {
                log.warn("allowed_origins is not an array, CORS denied, projectId={}", project.getId());
                return new AllowedOrigins(false, List.of());
            }
            List<String> origins = new ArrayList<>();
            for (JsonNode item : node) {
                if (!item.isTextual()) {
                    log.warn("allowed_origins contains non-string value, CORS denied, projectId={}",
                            project.getId());
                    return new AllowedOrigins(false, List.of());
                }
                origins.add(item.textValue());
            }
            return new AllowedOrigins(false, List.copyOf(origins));
        }
        catch (Exception exception) {
            log.warn("allowed_origins parse failed, CORS denied, projectId={}", project.getId());
            return new AllowedOrigins(false, List.of());
        }
    }

    private record AllowedOrigins(boolean wildcard, List<String> values) {
    }

}
