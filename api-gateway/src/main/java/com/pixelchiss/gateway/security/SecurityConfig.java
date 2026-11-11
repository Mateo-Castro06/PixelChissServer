package com.pixelchiss.gateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${PIXELCHISS_METRICS_KEY:}") String metricsKey) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/prometheus").access((authentication, context) ->
                        new AuthorizationDecision(metricsAllowed(metricsKey, context.getRequest().getHeader("X-Metrics-Key"))))
                        .requestMatchers(HttpMethod.GET, "/api/game/ws").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/game/openapi", "/api/game/swagger-ui/**").permitAll()
                        .requestMatchers(request -> request.getDispatcherType() == DispatcherType.FORWARD
                                && "/game-swagger.html".equals(request.getServletPath())).permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/api/refresh",
                                "/api/logout",
                                "/oauth2/**",
                                "/login/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }
    private static boolean metricsAllowed(String expected, String supplied) {
        return expected.length() >= 32 && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
