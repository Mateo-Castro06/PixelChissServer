package com.pixelchiss.gameservice.websocket;

import com.pixelchiss.gameservice.dto.Action;
import com.pixelchiss.gameservice.engine.GameException;
import com.pixelchiss.gameservice.service.GameService;
import com.pixelchiss.gameservice.service.PresenceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.pixelchiss.gameservice.engine.GameException.require;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final long AUTH_TIMEOUT_MILLIS = 10_000;
    private static final long PONG_TIMEOUT_MILLIS = 30_000;
    private static final int MESSAGE_SIZE_LIMIT = 16_384;
    private static final int SEND_TIMEOUT_MILLIS = 3000;
    private static final int SEND_BUFFER_SIZE = 65_536;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final JwtDecoder jwt;
    private final GameService games;
    private final PresenceService presence;
    private final Clock clock;

    private static class Connection {
        final WebSocketSession socket;
        final long openedAt;
        volatile String actor;
        volatile String gameId;
        volatile long expiresAt;
        volatile long lastPongAt;

        Connection(WebSocketSession session, long now) {
            socket = new ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MILLIS, SEND_BUFFER_SIZE);
            openedAt = now;
            lastPongAt = now;
        }
    }

    public GameWebSocketHandler(ObjectMapper json, JwtDecoder jwt, GameService games,
                                PresenceService presence, Clock clock) {
        this.json = json;
        this.jwt = jwt;
        this.games = games;
        this.presence = presence;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MESSAGE_SIZE_LIMIT);
        connections.put(session.getId(), new Connection(session, clock.millis()));
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Connection connection = connections.get(session.getId());
        if (connection == null) return;
        String requestId = null;
        try {
            JsonNode payload = json.readTree(message.getPayload());
            String type = payload.path("type").asString();
            requestId = payload.path("requestId").asString(null);
            if (connection.actor == null) {
                authenticate(connection, type, payload);
                return;
            }
            require(clock.millis() < connection.expiresAt, "TOKEN_EXPIRED", "Reconnect with a fresh access token");
            Action action = json.treeToValue(payload, Action.class);
            dispatch(connection, type, action);
        } catch (JwtException error) {
            sendError(connection, requestId, "INVALID_TOKEN", "Access token invalid");
            connection.socket.close(CloseStatus.POLICY_VIOLATION);
        } catch (GameException error) {
            sendError(connection, requestId, error.code(), error.getMessage());
            if (connection.actor == null || error.code().equals("TOKEN_EXPIRED")) {
                connection.socket.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (JacksonException | IllegalArgumentException error) {
            sendError(connection, requestId, "INVALID_JSON", "Invalid message shape");
        } catch (RuntimeException error) {
            log.warn("Game action failed ({})", error.getClass().getSimpleName());
            sendError(connection, requestId, "SERVICE_UNAVAILABLE", "State unavailable; synchronize before retrying");
        }
    }

    private void authenticate(Connection connection, String type, JsonNode payload) throws IOException {
        require(type.equals("AUTH"), "UNAUTHENTICATED", "First message must authenticate");
        require(clock.millis() - connection.openedAt < AUTH_TIMEOUT_MILLIS,
                "AUTH_TIMEOUT", "Authentication deadline expired");
        Jwt token = jwt.decode(payload.path("token").asString());
        require(token.getSubject() != null && !token.getSubject().isBlank() && token.getExpiresAt() != null,
                "INVALID_TOKEN", "Token subject and expiry required");
        connection.expiresAt = token.getExpiresAt().toEpochMilli();
        connection.actor = token.getSubject();
        send(connection, Map.of("type", "AUTHENTICATED", "playerId", connection.actor));
    }

    private void dispatch(Connection connection, String type, Action action) throws IOException {
        if (type.equals("JOIN_GAME") || type.equals("SYNC_REQUEST")) {
            join(connection, action.gameId());
            return;
        }
        require(connection.gameId != null && connection.gameId.equals(action.gameId()),
                "JOIN_REQUIRED", "Join the game before acting");
        send(connection, games.action(connection.actor, action));
    }

    private void join(Connection connection, String gameId) throws IOException {
        games.authorizeConnection(gameId, connection.actor);
        require(connection.gameId == null || connection.gameId.equals(gameId),
                "ALREADY_JOINED", "One game per connection");
        connection.gameId = gameId;
        presence.touch(gameId, connection.actor, connection.socket.getId());
        games.reconnect(gameId, connection.actor);
        send(connection, games.synchronization(gameId, connection.actor));
    }

    private void sendError(Connection connection, String requestId, String code, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "ERROR");
        error.put("requestId", requestId);
        error.put("code", code);
        error.put("message", message);
        error.put("gameId", connection.gameId);
        if (connection.gameId != null) {
            try {
                error.put("version", games.snapshot(connection.gameId, connection.actor).version());
            } catch (RuntimeException ignored) {
                // A storage failure must not suppress the original action error.
            }
        }
        send(connection, error);
    }

    private void send(Connection connection, Object value) throws IOException {
        connection.socket.sendMessage(new TextMessage(json.writeValueAsString(value)));
    }

    public void broadcast(byte[] payload) {
        try {
            String text = new String(payload, StandardCharsets.UTF_8);
            String gameId = json.readTree(text).path("gameId").asString();
            for (Connection connection : connections.values()) {
                if (gameId.equals(connection.gameId) && clock.millis() < connection.expiresAt) {
                    sendPublication(connection, text);
                }
            }
        } catch (RuntimeException error) {
            log.warn("Invalid game publication ({})", error.getClass().getSimpleName());
        }
    }

    private void sendPublication(Connection connection, String text) {
        try {
            connection.socket.sendMessage(new TextMessage(text));
        } catch (IOException | RuntimeException error) {
            try {
                connection.socket.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // Connection cleanup and Redis lease expiry handle failed close operations.
            }
        }
    }

    @Override
    public void handlePongMessage(WebSocketSession session, PongMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection != null) connection.lastPongAt = clock.millis();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection connection = connections.remove(session.getId());
        if (connection == null || connection.gameId == null) return;
        try {
            presence.remove(connection.gameId, connection.actor, session.getId());
        } catch (RuntimeException error) {
            log.warn("Presence release failed; lease will expire");
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void maintainConnections() {
        for (Connection connection : connections.values()) maintainConnection(connection);
    }

    private void maintainConnection(Connection connection) {
        try {
            if (connectionExpired(connection, clock.millis())) {
                connection.socket.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            if (connection.gameId != null) {
                presence.touch(connection.gameId, connection.actor, connection.socket.getId());
            }
            connection.socket.sendMessage(new PingMessage());
        } catch (IOException | RuntimeException error) {
            log.debug("Connection maintenance failed");
        }
    }

    private boolean connectionExpired(Connection connection, long now) {
        boolean authenticationExpired = connection.actor == null && now - connection.openedAt >= AUTH_TIMEOUT_MILLIS;
        boolean tokenExpired = connection.actor != null && now >= connection.expiresAt;
        return authenticationExpired || tokenExpired || now - connection.lastPongAt > PONG_TIMEOUT_MILLIS;
    }
}
