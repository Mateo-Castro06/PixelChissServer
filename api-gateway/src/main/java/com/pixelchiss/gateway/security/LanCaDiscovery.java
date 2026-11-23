package com.pixelchiss.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/** Public candidate only. Downloading this document never establishes trust. */
@Configuration
@Profile("lan")
public class LanCaDiscovery {
    public static final String PATH = "/.well-known/pixelchiss/ca";
    private static final int MAX_PEM_BYTES = 16384;
    private static final Pattern PEM = Pattern.compile(
            "\\A-----BEGIN CERTIFICATE-----\\s+([A-Za-z0-9+/=\\r\\n]+)\\s*-----END CERTIFICATE-----\\s*\\z");

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> lanCaDiscoveryFilter(
            @Value("${pixelchiss.lan.ca-certificate:/app/tls/ca.crt}") String certificatePath) {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("X-Content-Type-Options", "nosniff");
                // No routing, user-supplied paths, redirects, credentials or filesystem browsing.
                if (!request.isSecure() || !PATH.equals(request.getRequestURI())) {
                    error(response, 404, "NOT_FOUND");
                } else if (!"GET".equals(request.getMethod())) {
                    response.setHeader("Allow", "GET");
                    error(response, 405, "METHOD_NOT_ALLOWED");
                } else if (request.getQueryString() != null || request.getContentLengthLong() > 0
                        || request.getHeader("Transfer-Encoding") != null) {
                    error(response, 400, "INVALID_DISCOVERY_REQUEST");
                } else {
                    String document;
                    try {
                        document = document(Path.of(certificatePath));
                    } catch (Exception unavailable) {
                        // Never return exception details, paths, keystores or raw malformed input.
                        error(response, 503, "LAN_CA_UNAVAILABLE");
                        return;
                    }
                    response.getWriter().write(document);
                }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setUrlPatterns(java.util.List.of("/.well-known/pixelchiss/*"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    static String document(Path path) throws Exception {
        byte[] input;
        try (var stream = Files.newInputStream(path)) {
            input = stream.readNBytes(MAX_PEM_BYTES + 1);
        }
        if (input.length > MAX_PEM_BYTES) throw new IOException("Invalid CA");
        var match = PEM.matcher(new String(input, StandardCharsets.US_ASCII));
        if (!match.matches()) throw new IOException("Invalid CA");
        byte[] der = Base64.getDecoder().decode(match.group(1).replaceAll("\\s", ""));
        var certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        if (!Arrays.equals(der, certificate.getEncoded()) || certificate.getBasicConstraints() < 0
                || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            throw new IOException("Invalid CA");
        }
        boolean[] usage = certificate.getKeyUsage();
        if (usage != null && (usage.length <= 5 || !usage[5])) throw new IOException("Invalid CA");
        certificate.checkValidity();
        certificate.verify(certificate.getPublicKey());
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(der)
                + "\n-----END CERTIFICATE-----\n";
        // All interpolated fields have restricted alphabets; PEM newlines are JSON escaped.
        return "{\"protocolVersion\":1,\"caId\":\"sha256:" + fingerprint
                + "\",\"fingerprintSha256\":\"" + fingerprint
                + "\",\"caPem\":\"" + pem.replace("\n", "\\n") + "\"}";
    }

    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }
}
