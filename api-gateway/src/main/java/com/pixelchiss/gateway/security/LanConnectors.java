package com.pixelchiss.gateway.security;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** One Gateway, two connectors: LAN TLS and a host-loopback-only published HTTP port. */
@Configuration
@Profile("lan")
public class LanConnectors {
    @Bean FilterRegistrationBean<OncePerRequestFilter> privateActuator() {
        var filter = new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                       FilterChain chain) throws ServletException, IOException {
                if (request.isSecure() && (request.getRequestURI().startsWith("/oauth2/")
                        || request.getRequestURI().startsWith("/login/oauth2/"))) {
                    response.setStatus(400);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Use Desktop OAuth with client loopback for LAN\"}");
                    return;
                }
                if (request.isSecure() && request.getRequestURI().startsWith("/actuator/")
                        && !request.getRequestURI().equals("/actuator/health")) {
                    response.setStatus(404);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Not found\"}");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
    @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> localConnector(
            @Value("${pixelchiss.lan.local-port:8082}") int localPort) {
        return factory -> {
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(localPort);
            factory.addAdditionalConnectors(connector);
        };
    }
}
