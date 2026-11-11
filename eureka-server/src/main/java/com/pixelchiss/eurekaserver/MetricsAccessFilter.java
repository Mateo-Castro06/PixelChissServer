package com.pixelchiss.eurekaserver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class MetricsAccessFilter extends OncePerRequestFilter {
    private final String key;

    MetricsAccessFilter(@Value("${PIXELCHISS_METRICS_KEY:}") String key) {
        this.key = key;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.startsWith("/actuator/") || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        String supplied = request.getHeader("X-Metrics-Key");
        if (key.length() < 32 || supplied == null || !MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(403);
            return;
        }
        chain.doFilter(request, response);
    }
}
