package com.smartrecruit.aiengine.config;

import com.smartrecruit.aiengine.filter.JwtAuthenticationFilter;
import com.smartrecruit.common.config.CustomAccessDeniedHandler;
import com.smartrecruit.common.config.CustomAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * AI-Engine 微服务安全配置。
 *
 * <p>按流量类型分别授权，默认拒绝未认证请求：
 * <ul>
 *   <li>{@code /api/v1/agent-capabilities/**}、{@code /api/v1/llm/**}、{@code /api/v1/agentscope/**}、
 *       {@code /api/v1/agents/internal/**} 只允许<b>服务间内部 JWT</b>，且必须携带对应的 scope；
 *       这些端点是模型调用、简历解析、筛选打分等高成本操作，不对外开放。</li>
 *   <li>{@code /api/v1/agents/**}、{@code /api/v2/agents/**}（Agent 注册 / 启停 / 配置 / 事件流）
 *       面向管理端，要求已认证的用户 JWT。</li>
 *   <li>仅健康检查与文档端点匿名放行。</li>
 * </ul>
 *
 * <p>方法级权限（{@code @PreAuthorize}）本轮不启用：{@code agent:view} / {@code agent:manage}
 * 等权限码与调用方需在后续 A2 轮逐条核对后再开启。
 *
 * @since 1.0.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 内部调用：Agent 能力（模型推理、简历解析、筛选、出题等）。 */
    private static final String SCOPE_AI_CAPABILITIES = "SCOPE_ai.capabilities.invoke";
    /** 内部调用：通用 LLM 对话。 */
    private static final String SCOPE_AI_LLM = "SCOPE_ai.llm.chat";
    /** 内部调用：AgentScope 评估 / 编排。 */
    private static final String SCOPE_AI_AGENTSCOPE = "SCOPE_ai.agentscope.invoke";
    /** 内部调用：Agent 任务与 Token 用量上报。 */
    private static final String SCOPE_AI_METRICS = "SCOPE_ai.metrics.write";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CustomAuthenticationEntryPoint authenticationEntryPoint,
                          CustomAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 健康检查 / 文档 / 错误页
                        .requestMatchers("/api/v1/agentscope/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**",
                                "/swagger-resources/**", "/swagger-ui/**").permitAll()
                        // 仅限服务间内部调用（按最小 scope 区分）
                        .requestMatchers("/api/v1/agent-capabilities/**").hasAuthority(SCOPE_AI_CAPABILITIES)
                        .requestMatchers("/api/v1/llm/**").hasAuthority(SCOPE_AI_LLM)
                        .requestMatchers("/api/v1/agentscope/**").hasAuthority(SCOPE_AI_AGENTSCOPE)
                        .requestMatchers("/api/v1/agents/internal/**").hasAuthority(SCOPE_AI_METRICS)
                        // 管理端：要求已认证用户 JWT
                        .requestMatchers("/api/v1/agents/**", "/api/v2/agents/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
