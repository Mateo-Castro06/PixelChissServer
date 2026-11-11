package com.pixelchiss.auth.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import jakarta.servlet.DispatcherType
import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.authorization.AuthorizationDecision

@Configuration
class SecurityConfig(
    private val oauth2LoginSuccessHandler: OAuth2LoginSuccessHandler,
    private val jwtAuthenticationConverter: JwtAuthenticationConverter
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity, @Value("\${PIXELCHISS_METRICS_KEY:}") metricsKey: String): SecurityFilterChain {
        http
            .csrf {
                it.disable()
            }
            .authorizeHttpRequests {
                it
                    .requestMatchers("/actuator/prometheus").access { _, context ->
                        val supplied = context.request.getHeader("X-Metrics-Key")
                        AuthorizationDecision(metricsKey.length >= 32 && supplied != null &&
                            MessageDigest.isEqual(metricsKey.toByteArray(), supplied.toByteArray()))
                    }
                    .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        "/oauth2/**",
                        "/login/**",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**"
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(
                ClientLoginParamsFilter(),
                OAuth2AuthorizationRequestRedirectFilter::class.java
            )
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