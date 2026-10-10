package com.pixelchiss.gameservice.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean public SecurityFilterChain gameSecurity(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a->a.requestMatchers("/ws/game","/actuator/health","/swagger-ui/**","/swagger-ui.html","/v3/api-docs/**","/internal/games").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o->o.jwt(j->{})).build();
    }
}
