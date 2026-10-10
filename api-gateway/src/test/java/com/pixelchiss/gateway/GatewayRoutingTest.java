package com.pixelchiss.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "EUREKA_URL=http://localhost:8761/eureka/"})
class GatewayRoutingTest {
    static final HttpServer BACKEND = backend();
    @LocalServerPort int port;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.public-key-location", () -> GatewayTestKeys.PUBLIC_KEY);
        for (String service : List.of("AUTH-SERVICE", "GAME-SERVICE")) {
            properties.add("spring.cloud.discovery.client.simple.instances." + service + "[0].uri",
                    () -> "http://localhost:" + BACKEND.getAddress().getPort());
        }
    }
    static HttpServer backend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String body = "{\"method\":\"" + exchange.getRequestMethod() + "\",\"path\":\"" +
                        exchange.getRequestURI().getPath() + "\",\"bearerForwarded\":" +
                        (exchange.getRequestHeaders().getFirst("Authorization") != null) + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @AfterAll static void stopBackend() { BACKEND.stop(0); }
    HttpResponse<String> request(String method, String path, String token) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
            if (token != null) request.header("Authorization", "Bearer " + token);
            return client.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void gameRestPathsKeepPrefixAndForwardBearer() throws Exception {
        for (String path : List.of("/api/game/board", "/api/game/games/game-123")) {
            var response = request("GET", path, GatewayTestKeys.token(900));
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"path\":\"" + path + "\""));
            assertTrue(response.body().contains("\"bearerForwarded\":true"));
        }
    }
    @Test void authenticationAndPrivateRoutesStayProtected() throws Exception {
        assertEquals(401, request("GET", "/api/game/board", null).statusCode());
        assertEquals(401, request("GET", "/api/game/board", "invalid").statusCode());
        assertEquals(401, request("GET", "/api/game/board", GatewayTestKeys.token(-120)).statusCode());
        assertEquals(401, request("POST", "/api/game/ws", null).statusCode());
        assertEquals(401, request("GET", "/game-swagger.html", null).statusCode());
        for (String path : List.of("/internal/games", "/api/game/internal/games", "/api/game/api/game/board", "/ws/game")) {
            assertEquals(404, request("GET", path, GatewayTestKeys.token(900)).statusCode());
        }
    }
    @Test void existingAuthMethodsAndRewritesArePreserved() throws Exception {
        for (String[] route : new String[][]{
                {"POST", "/api/refresh", "/api/auth/refresh"},
                {"POST", "/api/logout", "/api/auth/logout"},
                {"GET", "/oauth2/authorization/google", "/oauth2/authorization/google"},
                {"GET", "/login/oauth2/code/google", "/login/oauth2/code/google"},
                {"GET", "/api/auth/me", "/api/auth/me"},
                {"PUT", "/api/auth/me/display-name", "/api/auth/me/display-name"}}) {
            String token = route[1].startsWith("/api/auth") ? GatewayTestKeys.token(900) : null;
            var response = request(route[0], route[1], token);
            assertEquals(200, response.statusCode(), route[1]);
            assertTrue(response.body().contains("\"path\":\"" + route[2] + "\""));
            assertTrue(response.body().contains("\"method\":\"" + route[0] + "\""));
        }
        assertEquals(401, request("GET", "/api/auth/me", null).statusCode());
    }
    @Test void optionalDiagnosticsAndPublicDocsHaveExactRewrites() throws Exception {
        for (String name : List.of("mongodb", "redis")) {
            String publicPath = "/api/game/test/" + name;
            assertEquals(401, request("GET", publicPath, null).statusCode());
            assertTrue(request("GET", publicPath, GatewayTestKeys.token(900)).body().contains("\"path\":\"/test/" + name + "\""));
        }
        assertTrue(request("GET", "/api/game/openapi", null).body().contains("/v3/api-docs/public-game"));
        var index = request("GET", "/api/game/swagger-ui/index.html", null);
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("url: \"/api/game/openapi\""));
        assertTrue(request("GET", "/api/game/swagger-ui/swagger-ui.css", null).body().contains("/swagger-ui/swagger-ui.css"));
    }
}
