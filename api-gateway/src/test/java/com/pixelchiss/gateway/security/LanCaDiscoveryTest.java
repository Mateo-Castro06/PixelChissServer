package com.pixelchiss.gateway.security;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("lan")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "EUREKA_URL=http://localhost:8761/eureka/", "pixelchiss.lan.local-port=0"})
class LanCaDiscoveryTest {
    static final LanTestCertificates TLS = new LanTestCertificates();
    static final EchoBackend BACKEND = new EchoBackend(TLS.directory);
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext application;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("server.ssl.key-store", () -> TLS.server.toUri().toString());
        properties.add("server.ssl.key-store-password", () -> LanTestCertificates.PASSWORD);
        properties.add("pixelchiss.lan.ca-certificate", () -> TLS.ca.toString());
        properties.add("spring.security.oauth2.resourceserver.jwt.public-key-location",
                () -> TLS.directory.resolve("jwt-public.pem").toUri().toString());
        properties.add("spring.cloud.discovery.client.simple.instances.GAME-SERVICE[0].uri", BACKEND::uri);
    }

    @AfterAll static void cleanup() throws Exception { BACKEND.close(); TLS.close(); }

    HttpResponse<String> request(SSLContext tls, String method, String host, String path, String body) throws Exception {
        try (var client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("https://" + host + ":" + port + path))
                    .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test void discoveryWithoutCredentialsThenStrictHttpsWithCandidateCa() throws Exception {
        // This test-only permissive context models the isolated, unauthenticated discovery request.
        // No production client or security configuration uses it.
        var unverified = SSLContext.getInstance("TLS");
        unverified.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        var response = request(unverified, "GET", "127.0.0.1", LanCaDiscovery.PATH, "");
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        var json = JsonMapper.builder().build().readTree(response.body());
        assertEquals(Set.of("protocolVersion", "caId", "fingerprintSha256", "caPem"),
                json.properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toSet()));
        assertEquals(1, json.get("protocolVersion").asInt());
        var candidate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(json.get("caPem").asString().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(candidate.getEncoded()));
        assertEquals(hash, json.get("fingerprintSha256").asString());
        assertEquals("sha256:" + hash, json.get("caId").asString());
        // Independent local trust source, not the fingerprint supplied by the download itself.
        assertArrayEquals(TLS.certificate.getEncoded(), candidate.getEncoded());
        assertEquals(200, request(LanTestCertificates.trust(candidate), "GET", "127.0.0.1", "/actuator/health", "").statusCode());
        assertEquals(401, request(LanTestCertificates.trust(candidate), "GET", "127.0.0.1", "/api/game/board", "").statusCode());
    }

    @Test void invalidMethodsQueriesAndPathsNeverExposeFilesOrWrite() throws Exception {
        var tls = LanTestCertificates.trust(TLS.certificate);
        byte[] original = Files.readAllBytes(TLS.ca);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")) {
            var response = request(tls, method, "127.0.0.1", LanCaDiscovery.PATH, "");
            assertEquals(405, response.statusCode(), method);
            assertEquals("GET", response.headers().firstValue("Allow").orElseThrow());
        }
        for (String suffix : List.of("?file=ca.p12", "?path=../../lan.env", "?file=server.key")) {
            var response = request(tls, "GET", "127.0.0.1", LanCaDiscovery.PATH + suffix, "");
            assertEquals(400, response.statusCode(), suffix);
            assertTrue(response.body().contains("INVALID_DISCOVERY_REQUEST"));
        }
        assertEquals(400, request(tls, "GET", "127.0.0.1", LanCaDiscovery.PATH, "invalid").statusCode());
        for (String suffix : List.of("/ca.p12", "/server.p12", "/.env", "/lan.env", "/server.key", "/", "/%2e%2e/ca.p12")) {
            var response = request(tls, "GET", "127.0.0.1", LanCaDiscovery.PATH + suffix, "");
            assertTrue(response.statusCode() >= 400, suffix);
            assertFalse(response.body().contains("BEGIN CERTIFICATE"));
        }
        assertArrayEquals(original, Files.readAllBytes(TLS.ca));
        assertFalse(request(tls, "GET", "127.0.0.1", LanCaDiscovery.PATH, "").body().contains(LanTestCertificates.PASSWORD));
    }

    @Test void unavailableOrInvalidCaReturnsSanitized503() throws Exception {
        var tls = LanTestCertificates.trust(TLS.certificate);
        byte[] original = Files.readAllBytes(TLS.ca);
        try {
            Files.delete(TLS.ca);
            assertUnavailable(tls);
            for (String invalid : List.of("PRIVATE secret", "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----",
                    Files.readString(TLS.directory.resolve("server.crt")), new String(original) + "PRIVATE secret", "x".repeat(16385))) {
                Files.writeString(TLS.ca, invalid);
                assertUnavailable(tls);
            }
        } finally { Files.write(TLS.ca, original); }
    }

    void assertUnavailable(SSLContext tls) throws Exception {
        var response = request(tls, "GET", "127.0.0.1", LanCaDiscovery.PATH, "");
        assertEquals(503, response.statusCode());
        assertEquals("{\"error\":\"LAN_CA_UNAVAILABLE\"}", response.body());
    }

    @Test void unknownCaAndIpOutsideSanFailStrictTls() throws Exception {
        assertThrows(javax.net.ssl.SSLHandshakeException.class,
                () -> request(SSLContext.getDefault(), "GET", "127.0.0.1", "/actuator/health", ""));
        assertThrows(javax.net.ssl.SSLHandshakeException.class,
                () -> request(LanTestCertificates.trust(TLS.certificate), "GET", "localhost", "/actuator/health", ""));
    }

    @Test void publicConnectorStillHidesMetricsAndRejectsWebOAuth() throws Exception {
        var tls = LanTestCertificates.trust(TLS.certificate);
        assertEquals(404, request(tls, "GET", "127.0.0.1", "/actuator/prometheus", "").statusCode());
        assertEquals(400, request(tls, "GET", "127.0.0.1", "/oauth2/authorization/google", "").statusCode());
        assertEquals(400, request(tls, "GET", "127.0.0.1", "/login/oauth2/code/google", "").statusCode());
        var tomcat = ((org.springframework.boot.tomcat.TomcatWebServer) application.getWebServer()).getTomcat();
        int localPort = java.util.Arrays.stream(tomcat.getService().findConnectors())
                .filter(connector -> !connector.getSecure()).findFirst().orElseThrow().getLocalPort();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localPort + LanCaDiscovery.PATH))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, response.statusCode());
            assertEquals("{\"error\":\"NOT_FOUND\"}", response.body());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + localPort + "/actuator/health"))
                    .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void expiredLeafAndCaAreRejectedAndRenewedSanKeepsCaIdentity() throws Exception {
        var factory = CertificateFactory.getInstance("X.509");
        TLS.signLeaf("expired", "ip:127.0.0.1", "-2d");
        X509Certificate expired;
        try (var input = Files.newInputStream(TLS.directory.resolve("expired.crt"))) {
            expired = (X509Certificate) factory.generateCertificate(input);
        }
        assertThrows(java.security.cert.CertificateExpiredException.class, expired::checkValidity);
        var parameters = new java.security.cert.PKIXParameters(Set.of(new java.security.cert.TrustAnchor(TLS.certificate, null)));
        parameters.setRevocationEnabled(false);
        assertThrows(java.security.cert.CertPathValidatorException.class,
                () -> java.security.cert.CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(List.of(expired)), parameters));
        String identity = LanCaDiscovery.document(TLS.ca);
        TLS.signLeaf("renewed", "ip:127.0.0.1,ip:10.50.60.70", null);
        X509Certificate renewed;
        try (var input = Files.newInputStream(TLS.directory.resolve("renewed.crt"))) {
            renewed = (X509Certificate) factory.generateCertificate(input);
        }
        java.security.cert.CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(List.of(renewed)), parameters);
        assertTrue(renewed.getSubjectAlternativeNames().stream().anyMatch(san -> san.equals(List.of(7, "10.50.60.70"))));
        assertEquals(identity, LanCaDiscovery.document(TLS.ca));
        // A different independent host has a different CA, even if its display name is identical.
        try (var other = new LanTestCertificates()) {
            assertNotEquals(identity, LanCaDiscovery.document(other.ca));
            assertThrows(javax.net.ssl.SSLHandshakeException.class,
                    () -> request(LanTestCertificates.trust(other.certificate), "GET", "127.0.0.1", "/actuator/health", ""));
            other.keytool("-genkeypair", "-alias", "expired-ca", "-dname", "CN=Expired test CA", "-keyalg", "RSA",
                    "-ext", "BC=ca:true", "-startdate", "-2d", "-validity", "1", "-keystore", "expired-ca.p12");
            other.keytool("-exportcert", "-alias", "expired-ca", "-rfc", "-file", "expired-ca.crt", "-keystore", "expired-ca.p12");
            assertThrows(java.security.cert.CertificateExpiredException.class,
                    () -> LanCaDiscovery.document(other.directory.resolve("expired-ca.crt")));
        }
    }

    @Test void strictWssStillProxiesFramesAndUnknownCaFails() throws Exception {
        try (var client = HttpClient.newBuilder().sslContext(LanTestCertificates.trust(TLS.certificate)).build()) {
            var received = new java.util.concurrent.CompletableFuture<String>();
            var socket = client.newWebSocketBuilder().buildAsync(URI.create("wss://127.0.0.1:" + port + "/api/game/ws"),
                    new java.net.http.WebSocket.Listener() {
                        public java.util.concurrent.CompletionStage<?> onText(java.net.http.WebSocket ws, CharSequence data, boolean last) {
                            received.complete(data.toString()); ws.request(1); return null;
                        }
                    }).get(10, java.util.concurrent.TimeUnit.SECONDS);
            socket.sendText("transport-only-test", true).get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("transport-only-test", received.get(5, java.util.concurrent.TimeUnit.SECONDS));
            socket.sendClose(1000, "done").get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (var client = HttpClient.newHttpClient()) {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.newWebSocketBuilder().buildAsync(URI.create("wss://127.0.0.1:" + port + "/api/game/ws"),
                            new java.net.http.WebSocket.Listener() {}).get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(javax.net.ssl.SSLHandshakeException.class, failure.getCause());
        }
    }

    /** Test transport, not a simulation of Game rules, sessions or OAuth. */
    static final class EchoBackend implements AutoCloseable {
        final org.apache.catalina.startup.Tomcat tomcat = new org.apache.catalina.startup.Tomcat();
        EchoBackend(java.nio.file.Path directory) {
            try {
                tomcat.setBaseDir(directory.resolve("tomcat").toString());
                tomcat.setPort(0);
                tomcat.getConnector().setProperty("address", "127.0.0.1");
                var context = tomcat.addContext("", directory.toString());
                org.apache.catalina.startup.Tomcat.addServlet(context, "default", new org.apache.catalina.servlets.DefaultServlet());
                context.addServletMappingDecoded("/", "default");
                context.addServletContainerInitializer(new org.apache.tomcat.websocket.server.WsSci(), Set.of(Echo.class));
                tomcat.start();
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        String uri() { return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort(); }
        public void close() throws Exception { tomcat.stop(); tomcat.destroy(); }
    }

    @jakarta.websocket.server.ServerEndpoint("/ws/game")
    public static class Echo {
        @jakarta.websocket.OnMessage public String echo(String message) { return message; }
    }
}
