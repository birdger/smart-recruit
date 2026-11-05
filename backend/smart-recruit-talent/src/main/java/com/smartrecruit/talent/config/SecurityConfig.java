package com.smartrecruit.talent.config;

import com.smartrecruit.talent.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Talent 微服务安全配置。
 *
 * <p>A3-1 起 {@link JwtAuthenticationFilter} <b>不再采信</b>网关注入的
 * {@code X-User-Id} / {@code X-Username} / {@code X-User-Role} 请求头
 * （直连端口可绕过网关伪造），身份只来自验签成功的用户 JWT 或服务间内部 JWT。
 * 业务代码仍通过 {@code SecurityContextHolder} 获取当前用户。</p>
 *
 * <p>本模块本轮未启用 method security，也未收紧 HTTP 层规则（当前为 {@code permitAll}），
 * 属于后续 A2 轮逐模块权限审计的范围。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
