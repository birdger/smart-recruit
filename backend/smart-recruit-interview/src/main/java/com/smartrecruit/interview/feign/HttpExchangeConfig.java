package com.smartrecruit.interview.feign;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.smartrecruit.common.security.InternalTokenService;

import java.time.Duration;
import java.util.List;

/**
 * HTTP Interface 客户端代理配置。
 *
 * <p>使用 Spring 6 / Boot 4 原生的 {@code @HttpExchange} 替代 OpenFeign。
 * 通过 {@code @LoadBalanced} RestClient 结合 Nacos 实现服务发现与负载均衡。</p>
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HttpExchangeConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpExchangeConfig.class);

    /** 本服务在内部令牌中的调用方标识，与 spring.application.name 一致。 */
    private static final String SERVICE_NAME = "smart-recruit-interview";
    /** 被调用方标识，与 System 的 spring.application.name 一致（用于 aud 校验）。 */
    private static final String SYSTEM_SERVICE_NAME = "smart-recruit-system";
    /** 被调用方标识，与 AI-Engine 的 spring.application.name 一致（用于 aud 校验）。 */
    private static final String AI_ENGINE_SERVICE_NAME = "smart-recruit-ai-engine";

    @Bean
    @LoadBalanced
    @Scope("prototype")
    RestClient.Builder loadBalancedBuilder() {
        return RestClient.builder();
    }

    @Bean
    RecruitmentClient recruitmentClient(RestClient.Builder builder) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder
                .baseUrl("http://smart-recruit-recruitment")
                .requestFactory(requestFactory)
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(RecruitmentClient.class);
    }

    @Bean
    AiEngineClient aiEngineClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(300));
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestFactory(requestFactory)
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, AI_ENGINE_SERVICE_NAME, List.of("ai.llm.chat")));
                })
                .build();
        AiEngineClient proxy = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiEngineClient.class);
        log.info("AiEngineClient Bean 创建成功: baseUrl=http://smart-recruit-ai-engine, readTimeout=300s, proxyClass={}",
                proxy.getClass().getSimpleName());
        return proxy;
    }

    @Bean
    AiAgentScopeClient aiAgentScopeClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(300));
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestFactory(requestFactory)
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, AI_ENGINE_SERVICE_NAME, List.of("ai.agentscope.invoke")));
                })
                .build();
        AiAgentScopeClient proxy = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiAgentScopeClient.class);
        log.info("AiAgentScopeClient Bean 创建成功: baseUrl=http://smart-recruit-ai-engine, readTimeout=300s");
        return proxy;
    }

    @Bean
    SystemClient systemClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        RestClient client = builder
                .baseUrl("http://smart-recruit-system")
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, SYSTEM_SERVICE_NAME,
                            List.of("users.read", "internal.users.read",
                                    "internal.email.send", "internal.notifications.send")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(SystemClient.class);
    }

    @Bean
    AiAgentCapabilityClient aiAgentCapabilityClient(RestClient.Builder builder,
                                                    InternalTokenService internalTokenService) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        // 出题等能力内部已改异步任务，但同步兜底路径也需要足够超时，
        // 避免 LLM 生成（可达数十秒）时客户端提前取消
        requestFactory.setReadTimeout(Duration.ofSeconds(300));
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestFactory(requestFactory)
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, AI_ENGINE_SERVICE_NAME, List.of("ai.capabilities.invoke")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiAgentCapabilityClient.class);
    }

    @Bean
    TalentClient talentClient(RestClient.Builder builder) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder
                .baseUrl("http://smart-recruit-talent")
                .requestFactory(requestFactory)
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(TalentClient.class);
    }
}
