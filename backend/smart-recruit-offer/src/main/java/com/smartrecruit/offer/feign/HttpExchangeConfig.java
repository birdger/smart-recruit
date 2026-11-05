package com.smartrecruit.offer.feign;

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
 * HTTP Interface 客户端代理配置（Offer 模块）。
 *
 * <p>使用 Spring 6 / Boot 4 原生的 {@code @HttpExchange} 替代 OpenFeign。
 * 通过 {@code @LoadBalanced} RestClient 结合 Nacos 实现服务发现与负载均衡。</p>
 *
 * @since 1.0.0
 */
@Configuration(proxyBeanMethods = false)
public class HttpExchangeConfig {

    /** 本服务在内部令牌中的调用方标识，与 spring.application.name 一致。 */
    private static final String SERVICE_NAME = "smart-recruit-offer";
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
        RestClient client = builder
                .baseUrl("http://smart-recruit-recruitment")
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(RecruitmentClient.class);
    }

    @Bean
    SystemClient systemClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        RestClient client = builder
                .baseUrl("http://smart-recruit-system")
                .requestFactory(requestFactory)
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, SYSTEM_SERVICE_NAME,
                            List.of("users.read", "users.create", "internal.users.read",
                                    "internal.notifications.send", "internal.email.send")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(SystemClient.class);
    }

    @Bean
    SystemConfigClient systemConfigClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        RestClient client = builder
                .baseUrl("http://smart-recruit-system")
                .requestFactory(requestFactory)
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, SYSTEM_SERVICE_NAME, List.of("configs.read")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(SystemConfigClient.class);
    }

    @Bean
    SystemFileClient systemFileClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        RestClient client = builder
                .baseUrl("http://smart-recruit-system")
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, SYSTEM_SERVICE_NAME,
                            List.of("files.upload", "files.download", "files.delete")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(SystemFileClient.class);
    }

    @Bean
    AiEngineClient aiEngineClient(RestClient.Builder builder, InternalTokenService internalTokenService) {
        RestClient client = builder
                .baseUrl("http://smart-recruit-ai-engine")
                .requestInitializer(request -> {
                    request.getHeaders().setBearerAuth(internalTokenService.generateToken(
                            SERVICE_NAME, AI_ENGINE_SERVICE_NAME,
                            List.of("ai.capabilities.invoke")));
                })
                .build();
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(client))
                .build()
                .createClient(AiEngineClient.class);
    }
}
