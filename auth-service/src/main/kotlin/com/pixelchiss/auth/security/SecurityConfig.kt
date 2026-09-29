package com.pixelchiss.auth.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig(
    private val oauth2LoginSuccessHandler: OAuth2LoginSuccessHandler,
    private val jwtAuthenticationConverter: JwtAuthenticationConverter
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf {
                it.disable()
            }
            .authorizeHttpRequests {
                it
                    .requestMatchers(
                        "/actuator/health",
                        "/oauth2/**",
                        "/login/**",
                        "/api/auth/refresh",
                        "/api/auth/logout"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2Login {
                it.successHandler(oauth2LoginSuccessHandler)
            }
            .oauth2ResourceServer {
                it.jwt {
                    it.jwtAuthenticationConverter(jwtAuthenticationConverter)
                }
            }

        return http.build()
    }
}