package com.pixelchiss.gameservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import com.pixelchiss.gameservice.persistence.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"REDIS_URL=redis://localhost:6379", "MONGODB_URI=mongodb://localhost:27017/pixelchiss",
        "EUREKA_URL=http://localhost:8761/eureka/", "JWT_PUBLIC_KEY_PATH=unused", "eureka.client.enabled=false", "game.seed.enabled=false"})
@AutoConfigureMockMvc
class GameServiceApplicationTests {
    @MockitoBean MongoTemplate mongo;
    @MockitoBean org.springframework.data.mongodb.gridfs.GridFsOperations gridFs;
    @MockitoBean GameRepository repository;
    @MockitoBean GameCatalog catalog;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean(name="gameEvents") RedisMessageListenerContainer events;
    @Autowired MockMvc mvc;

    @Test
    void contextLoads() {
    }
    @Test void swaggerLoadsAndDocumentsBearerAndLobbyCredential() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.BearerJWT.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/internal/games'].post").exists())
                .andExpect(jsonPath("$.paths['/api/game/games/{gameId}'].get").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
    @Test void restRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/game/board")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/game/games/g")).andExpect(status().isUnauthorized());
    }
}
