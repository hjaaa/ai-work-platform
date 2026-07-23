package com.aiwork.baas.data.enduser;

import com.aiwork.baas.data.config.DataPlaneProperties;
import com.aiwork.baas.data.context.DataRequestContext;
import com.aiwork.baas.data.context.DataRole;
import com.aiwork.baas.data.error.DataApiException;
import com.aiwork.baas.data.rest.BoundedInputStream;
import com.aiwork.common.security.annotation.Inner;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * 终端用户 Auth 端点(spec §7.2/§7.6):复用数据面管道与独立 ObjectMapper,
 * body 手动读写不经平台 HttpMessageConverter。
 *
 * @author ai-work
 * @date 2026/07/22
 */
@Inner(false)
@RestController
@RequestMapping("/data/{projectRef}/auth/v1")
public class EndUserAuthController {

    private final EndUserAuthService authService;

    private final ClientIpResolver ipResolver;

    private final ObjectMapper objectMapper;

    private final DataPlaneProperties properties;

    public EndUserAuthController(EndUserAuthService authService, ClientIpResolver ipResolver,
            @Qualifier("dataPlaneObjectMapper") ObjectMapper objectMapper, DataPlaneProperties properties) {
        this.authService = authService;
        this.ipResolver = ipResolver;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostMapping("/signup")
    public void signup(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeJson(response, 200,
                authService.signup(context(request), readBody(request), ipResolver.resolve(request)));
    }

    @PostMapping("/token")
    public void token(@RequestParam(value = "grant_type", required = false) String grantType,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("password".equals(grantType)) {
            writeJson(response, 200,
                    authService.login(context(request), readBody(request), ipResolver.resolve(request)));
            return;
        }
        if ("refresh_token".equals(grantType)) {
            writeJson(response, 200, authService.refresh(context(request), readBody(request)));
            return;
        }
        throw DataApiException.badRequest("grant_type 仅支持 password 或 refresh_token");
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(requireAuthenticated(request));
        response.setStatus(204);
    }

    @GetMapping("/user")
    public void user(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeJson(response, 200, authService.currentUser(requireAuthenticated(request)));
    }

    @PutMapping("/user/password")
    public void changePassword(HttpServletRequest request, HttpServletResponse response) throws IOException {
        authService.changePassword(requireAuthenticated(request), readBody(request), ipResolver.resolve(request));
        response.setStatus(204);
    }

    static DataRequestContext context(HttpServletRequest request) {
        DataRequestContext ctx = (DataRequestContext) request.getAttribute(DataRequestContext.ATTRIBUTE);
        // §7.6:secret key 一律不得调用 auth 端点。ApiKeyAuthFilter 基于原始 getRequestURI() 判定 auth
        // 路径,在百分号编码(如 %61uth)/矩阵变量等形态下与 Spring 路由的归一化匹配结果不一致,存在
        // 绕过面;此处在路由已解析后用鉴权结果(SERVICE_ROLE ⟺ secret key)兜底,任何编码形态下都返回 403。
        if (ctx.role() == DataRole.SERVICE_ROLE) {
            throw DataApiException.forbidden("secret key 不得调用 auth 端点", null);
        }
        return ctx;
    }

    private static DataRequestContext requireAuthenticated(HttpServletRequest request) {
        DataRequestContext ctx = context(request);
        if (ctx.role() != DataRole.AUTHENTICATED) {
            throw DataApiException.unauthorized("缺少有效的 access JWT");
        }
        return ctx;
    }

    /**
     * 流式兜底计数读取 body(spec §13:Content-Length 预检之外的第二道防线)。
     * 复用数据面 {@link BoundedInputStream} 与 {@code baas.data.body-max-bytes}(默认 1 MiB):
     * 缺失或 chunked Content-Length 时,过滤器预检失效,靠此逐字节计数在超限时抛 413,避免内存放大。
     */
    private JsonNode readBody(HttpServletRequest request) throws IOException {
        // 与 DataRestController.readBody 同款:不 try-with-resources(servlet 输入流由容器管理)
        InputStream bounded = new BoundedInputStream(request.getInputStream(), properties.getBodyMaxBytes());
        try {
            JsonNode body = objectMapper.readTree(bounded);
            if (body == null || body.isMissingNode()) {
                // 空 body 视作空对象:各端点自行校验必需字段
                return objectMapper.createObjectNode();
            }
            return body;
        }
        catch (DataApiException exception) {
            throw exception; // BoundedInputStream 抛出的 413 直接透出
        }
        catch (JacksonException exception) {
            throw DataApiException.badRequest("请求体不是合法 JSON");
        }
    }

    private void writeJson(HttpServletResponse response, int status, ObjectNode body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

}
