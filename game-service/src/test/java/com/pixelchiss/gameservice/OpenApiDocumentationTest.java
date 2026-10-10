package com.pixelchiss.gameservice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "game.integration", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "REDIS_URL=redis://localhost:6379/0", "MONGODB_URI=mongodb://localhost:27017/pixelchiss",
        "EUREKA_URL=http://localhost:8761/eureka/", "eureka.client.enabled=false",
        "game.scheduling.enabled=false", "game.diagnostics.enabled=true"})
@DirtiesContext
class OpenApiDocumentationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;
    @DynamicPropertySource static void publicKey(DynamicPropertyRegistry properties) throws Exception {
        InfrastructureIntegrationTest.properties(properties);
    }
    JsonNode get(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return json.readTree(response.body());
        }
    }
    @Test void allFiveControllerOperationsMatchOpenApiWithTypedBodiesErrorsAndSecurity() throws Exception {
        var spec = get("/v3/api-docs");
        Map<String, String> expected = Map.of("/api/game/board", "get", "/api/game/games/{gameId}", "get",
                "/internal/games", "post", "/test/mongodb", "get", "/test/redis", "get");
        assertEquals(expected.keySet(), Set.copyOf(spec.path("paths").propertyNames()));
        var actual = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().equals("com.pixelchiss.gameservice.controller"))
                .flatMap(entry -> entry.getKey().getPatternValues().stream()).collect(java.util.stream.Collectors.toSet());
        assertEquals(expected.keySet(), actual);
        expected.forEach((path, method) -> {
            var operation = spec.path("paths").path(path).path(method);
            assertFalse(operation.isMissingNode(), path);
            assertFalse(operation.path("responses").path("200").path("description").asString().isBlank());
            assertTrue(operation.path("responses").has(path.equals("/internal/games") ? "403" : "401"));
            assertTrue(operation.path("security").toString().contains(path.equals("/internal/games") ? "LobbyToken" : "BearerJWT"));
        });
        var creation = spec.path("paths").path("/internal/games").path("post");
        assertTrue(creation.path("requestBody").path("required").asBoolean());
        assertTrue(creation.path("requestBody").toString().contains("CreateGame"));
        assertTrue(creation.path("parameters").toString().contains("X-Lobby-Token"));
        for (String status : Set.of("400", "403", "409", "503")) {
            assertTrue(creation.path("responses").path(status).toString().contains("#/components/schemas/Error"));
        }
        assertTrue(spec.path("paths").path("/api/game/board").path("get").path("responses").path("200").toString().contains("BoardLayout"));
        var schemas = spec.path("components").path("schemas");
        assertTrue(schemas.path("BoardLayout").path("properties").has("routes"));
        assertTrue(schemas.path("CreateGame").path("required").toString().contains("creatorPlayerId"));
        assertTrue(schemas.path("Participant").path("required").toString().contains("seat"));
    }
    @Test void publicSpecificationUsesOnlyGatewayPathsAndExcludesInternalInitialization() throws Exception {
        var publicSpec = get("/v3/api-docs/public-game");
        assertEquals(Set.of("/api/game/board", "/api/game/games/{gameId}", "/api/game/test/mongodb", "/api/game/test/redis"),
                Set.copyOf(publicSpec.path("paths").propertyNames()));
        assertEquals("/", publicSpec.path("servers").get(0).path("url").asString());
        assertEquals("/test/redis", publicSpec.path("paths").path("/api/game/test/redis").path("x-internal-path").asString());
        assertFalse(publicSpec.path("paths").has("/internal/games"));
        assertFalse(publicSpec.path("paths").has("/api/game/ws"));
    }
}
