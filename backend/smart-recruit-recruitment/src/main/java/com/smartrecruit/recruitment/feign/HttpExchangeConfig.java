package com.smartrecruit.recruitment.feign;

import com.smartrecruit.common.security.InternalTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.List;

/**
 * HTTP Interface 客户端代理配置（Recruitment → System + AI-Engine）。
 *
 * <h3>调用 smart-recruit-system 的新路径（签名内部 JWT）</h3>
 * <pre>
 * Recruitment RestClient
 *   ├─ RestClient.Builder 注入 requestFilter → 每次请求自动注入
 *   │   Authorization: Bearer <内部签名 JWT>
 *   ├─ baseUrl = {@code target-system-url}（环境变量覆盖，默认 127.0.0.1:8081 绕过 Nacos）
 *   └─ JwtAuthenticationFilter(System) 验证内部令牌 → scope 满足 → 放行
 * </pre>
 *
 * <h3>调用 smart-recruit-ai-engine（暂不变）</h3>
 * 继续使用 @LoadBalanced builder + service name baseUrl（AI-Engine 尚未接入内部令牌校验）。
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HttpExchangeConfig {

    // ================================================================
    // 配置注入
    // ================================================================

    @Value("${spring.application.name}")
    private String callerApplicationName;

    @Value("${smart-recruit.internal.target-system:smart-recruit-system}")
    private String targetSystemName;

    @Value("${smart-recruit.internal.target-system-url:http://127.0.0.1:8181}")
    private String targetSystemUrl;

    private final InternalTokenService internalTokenService;

    public HttpExchangeConfig(InternalTokenService internalTokenService) {
        this.internalTokenService = internalTokenService;
    }

    // ================================================================
    // @LoadBalanced builder（给 AI-Engine 用，暂不改变）
    // ================================================================

    @Bean
    @LoadBalanced
    @Scope("prototype")
    RestClient.Builder loadBalancedBuilder() {
        return RestClient.builder();
    }

    // ================================================================
    // System 客户端：内部签名 JWT 认证（闭环 C1 + C11a 所属）
    // ================================================================

    @Bean
    SystemFileClient systemFileClient() {
        return buildClient(SystemFileClient.class,
                List.of("files.upload", "files.download", "files.delete"));
    }

    @Bean
    SystemUserClient systemUserClient() {
        return buildClient(SystemUserClient.class, List.of("users.read"));
    }

    @Bean
    SystemNotificationClient systemNotificationClient() {
        return buildClient(SystemNotificationClient.class, List.of("internal.notifications.send"));
    }

    @Bean
    SystemConfigClient systemConfigClient() {
        return buildClient(SystemConfigClient.class, List.of("configs.read"));
    }

    /**
     * 构造带内部签名 JWT 的 System 客户端。
     *
     * <p>⚠️ 内部 JWT 有效期仅 15 分钟，不能在 Bean 创建时一次性生成固定 token。
     * 每次 HTTP 请求通过 {@code requestFilter} 动态生成新 token，
     * 保证 RestClient 在长期运行期间始终携带有效令牌。
     *
     * @param clientClass 客户端接口类型
     * @param scopes      本次调用链需要的最小 scope
     */
    private <T> T buildClient(Class<T> clientClass, List<String> scopes) {
        RestClient client = RestClient.builder()
                .baseUrl(targetSystemUrl)
                // 每次请求动态生成新内部 JWT（避免 15 min 过期）；已无 INNER-REQUEST 旧头
                .requestInitializer(builder -> {
                    String freshToken = internalTokenService.generateToken(
                            callerApplicationName, targetSystemName, scopes);
                    builder.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + freshToken);
                })
                .build();

        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(clientClass);
    }

    // ================================================================
    // AI-Engine 客户端：内部签名 JWT 认证
    // ================================================================

    @Bean
    AiAgentCapabilityClient aiAgentCapabilityClient(RestClient.Builder builder) {
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            callerApplicationName, "smart-recruit-ai-engine",
                            List.of("ai.capabilities.invoke")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiAgentCapabilityClient.class);
    }

    @Bean
    AiAgentLlmClient aiAgentLlmClient(RestClient.Builder builder) {
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            callerApplicationName, "smart-recruit-ai-engine",
                            List.of("ai.llm.chat")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiAgentLlmClient.class);
    }

    @Bean
    AiAgentTaskClient aiAgentTaskClient(RestClient.Builder builder) {
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            callerApplicationName, "smart-recruit-ai-engine",
                            List.of("ai.metrics.write")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiAgentTaskClient.class);
    }
}
