package com.pixelchiss.gateway.websocket;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Adds raw WebSocket proxying while keeping the existing Gateway WebMVC HTTP routes. */
@Component
public class GameWebSocketProxy extends AbstractWebSocketHandler implements HandshakeInterceptor {
    public static final String PUBLIC_PATH = "/api/game/ws";
    private static final String BRIDGE_ATTRIBUTE = GameWebSocketProxy.class.getName() + ".bridge";
    private static final long CONNECT_TIMEOUT_MILLIS = 5000;
    private final LoadBalancerClient discovery;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS)).build();
    private final Map<String, GameSocketBridge> connections = new ConcurrentHashMap<>();
    private final long pongTimeoutMillis;

    public GameWebSocketProxy(LoadBalancerClient discovery,
                             @Value("${gateway.game.pong-timeout-millis:30000}") long pongTimeoutMillis) {
        this.discovery = discovery;
        this.pongTimeoutMillis = pongTimeoutMillis;
    }

    @Override public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                              WebSocketHandler handler, Map<String, Object> attributes) {
        if (request.getURI().getRawQuery() != null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        GameSocketBridge bridge = new GameSocketBridge();
        try {
            ServiceInstance instance = discovery.choose("GAME-SERVICE");
            if (instance == null) {
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }
            URI destination = new URI(instance.isSecure() ? "wss" : "ws", null,
                    instance.getHost(), instance.getPort(), "/ws/game", null, null);
            var builder = http.newWebSocketBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS));
            String origin = request.getHeaders().getOrigin();
            if (origin != null) builder.header("Origin", origin);
            builder.buildAsync(destination, bridge).get(CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            attributes.put(BRIDGE_ATTRIBUTE, bridge);
            if (request instanceof ServletServerHttpRequest servlet) {
                servlet.getServletRequest().setAttribute(BRIDGE_ATTRIBUTE, bridge);
            }
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof WebSocketHandshakeException rejected) {
                response.setStatusCode(HttpStatus.valueOf(rejected.getResponse().statusCode()));
            } else response.setStatusCode(HttpStatus.BAD_GATEWAY);
        } catch (Exception error) {
            response.setStatusCode(HttpStatus.BAD_GATEWAY);
        }
        bridge.close(CloseStatus.SERVER_ERROR);
        return false;
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler handler, Exception exception) {
        if (exception != null && request instanceof ServletServerHttpRequest servlet
                && servlet.getServletRequest().getAttribute(BRIDGE_ATTRIBUTE) instanceof GameSocketBridge bridge) {
            bridge.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        GameSocketBridge bridge = bridge(session);
        connections.put(session.getId(), bridge);
        bridge.attach(session);
    }

    @Override public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        GameSocketBridge bridge = bridge(session);
        if (message instanceof PongMessage) bridge.pongReceived();
        else {
            try { bridge.forward(message); }
            catch (Exception error) { bridge.close(CloseStatus.SERVER_ERROR); }
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        GameSocketBridge bridge = connections.remove(session.getId());
        if (bridge != null) bridge.close(status);
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        bridge(session).close(CloseStatus.SERVER_ERROR);
    }

    private GameSocketBridge bridge(WebSocketSession session) {
        return (GameSocketBridge) session.getAttributes().get(BRIDGE_ATTRIBUTE);
    }

    @Scheduled(fixedDelayString = "${gateway.game.heartbeat-millis:5000}")
    public void heartbeat() {
        long now = System.currentTimeMillis();
        connections.values().forEach(bridge -> bridge.heartbeat(now, pongTimeoutMillis));
    }

    @PreDestroy public void shutdown() {
        connections.values().forEach(bridge -> bridge.close(CloseStatus.GOING_AWAY));
        http.shutdownNow();
    }
}
