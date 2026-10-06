package com.company.yoga.config;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @org.springframework.beans.factory.annotation.Value("${yoga.cors.origins:http://localhost:4200,http://127.0.0.1:4200}")
    private String corsOrigins;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> { res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}"); })
                .accessDeniedHandler((req, res, ex) -> { res.setStatus(403); res.setContentType("application/json"); res.getWriter().write("{\"success\":false,\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}"); }))
            .authorizeHttpRequests(auth -> auth
                // 1. Công khai: Xác thực & Đăng ký (chỉ STUDENT), API Docs, Actuator
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/actuator/**"
                ).permitAll()

                // 2. Công khai các tra cứu đọc thông tin (để xem lịch, cơ sở, gói tập)
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/branches/**",
                    "/api/v1/schedules/**",
                    "/api/v1/membership-plans/**"
                ).permitAll()

                // 3. Toàn bộ các API nghiệp vụ khác yêu cầu xác thực JWT Token
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(java.util.Arrays.stream(corsOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Branch-Id"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
