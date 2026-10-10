package com.pixelchiss.gateway.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableScheduling
public class GameWebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketProxy proxy;
    private final String[] origins;

    public GameWebSocketConfig(GameWebSocketProxy proxy,
            @Value("${GATEWAY_GAME_ALLOWED_ORIGINS:http://localhost:8080}") String[] origins) {
        this.proxy = proxy;
        this.origins = origins;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(proxy, GameWebSocketProxy.PUBLIC_PATH).addInterceptors(proxy).setAllowedOrigins(origins);
    }
}
