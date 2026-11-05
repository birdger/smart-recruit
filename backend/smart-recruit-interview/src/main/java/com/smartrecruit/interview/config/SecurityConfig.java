package com.smartrecruit.interview.config;

import com.smartrecruit.common.config.CustomAccessDeniedHandler;
import com.smartrecruit.common.config.CustomAuthenticationEntryPoint;
import com.smartrecruit.interview.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Interview 微服务安全配置。
 *
 * <p>本轮（A3-1）只落实 <b>HTTP 层最小保护</b>：
 * <ul>
 *   <li>真正需要匿名访问的公开测评端点（候选人邮件链接）继续 {@code permitAll}；</li>
 *   <li>其余端点一律 {@code authenticated()}，不再默认放行；
 *       认证既可来自网关签发的用户 JWT，也可来自服务间内部 JWT（如 Talent 读取面试统计）；</li>
 *   <li>认证全部由 {@link JwtAuthenticationFilter} 验签完成，不信任任何身份请求头。</li>
 * </ul>
 *
 * <p>方法级权限（{@code @PreAuthorize}）本轮<b>不启用</b>：现有 {@code interview:view} 等
 * 权限码尚未逐条核对调用方，需在后续 A2 轮逐模块审计后再开启，避免误伤已有业务。
 *
 * @since 1.0.0
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
                        // 候选人公开测评（邮件链接，无登录态）
                        .requestMatchers("/api/v1/public/assessment", "/api/v1/public/assessment/**").permitAll()
                        // 基础设施端点
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // Swagger / Knife4j
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**",
                                "/swagger-resources/**", "/swagger-ui/**").permitAll()
                        // 其余全部要求已认证（用户 JWT 或服务间内部 JWT）
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
