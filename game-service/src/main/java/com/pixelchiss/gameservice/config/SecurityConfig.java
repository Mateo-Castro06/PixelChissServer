package com.pixelchiss.gameservice.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean public SecurityFilterChain gameSecurity(HttpSecurity http, @Value("${PIXELCHISS_METRICS_KEY:}") String metricsKey) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/actuator/prometheus").access((authentication, context) ->
                        new AuthorizationDecision(metricsAllowed(metricsKey, context.getRequest().getHeader("X-Metrics-Key"))))
                        .requestMatchers("/ws/game","/actuator/health","/swagger-ui/**","/swagger-ui.html","/v3/api-docs/**","/internal/games").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
    private static boolean metricsAllowed(String expected, String supplied) {
        return expected.length() >= 32 && supplied != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
