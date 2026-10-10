package com.pixelchiss.gateway.websocket;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GameSocketBridgeTest {
    final WebSocket upstream = mock(WebSocket.class);
    final WebSocketSession client = mock(WebSocketSession.class);
    GameSocketBridge bridge() throws Exception {
        when(upstream.sendClose(anyInt(), anyString())).thenReturn(CompletableFuture.completedFuture(upstream));
        when(upstream.sendText(anyString(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(upstream));
        when(client.isOpen()).thenReturn(true);
        var bridge = new GameSocketBridge();
        bridge.onOpen(upstream);
        bridge.attach(client);
        return bridge;
    }
    @Test void forwardsAuthUnchangedAndReassemblesDownstreamTextFragments() throws Exception {
        var bridge = bridge();
        bridge.forward(new TextMessage("{\"type\":\"AUTH\",\"token\":\"fixture\"}"));
        verify(upstream).sendText("{\"type\":\"AUTH\",\"token\":\"fixture\"}", true);
        bridge.onText(upstream, "{\"type\":\"AUTH", false);
        verify(client, never()).sendMessage(any());
        bridge.onText(upstream, "ENTICATED\"}", true);
        verify(client).sendMessage(argThat(message -> message.getPayload().equals("{\"type\":\"AUTHENTICATED\"}")));
    }
    @Test void backendPolicyClosureClosesThePublicConnection() throws Exception {
        bridge().onClose(upstream, 1008, "");
        verify(client).close(CloseStatus.POLICY_VIOLATION);
    }
    @Test void missingPublicPongClosesBothHopsDespiteAutomaticUpstreamPongs() throws Exception {
        bridge().heartbeat(System.currentTimeMillis() + 40_000, 30_000);
        verify(client).close(CloseStatus.POLICY_VIOLATION);
        verify(upstream).sendClose(1008, "");
    }
    @Test void messageSizeLimitBoundsBackendFragments() throws Exception {
        bridge().onText(upstream, "x".repeat(GameSocketBridge.MAX_RESPONSE_BYTES + 1), false);
        verify(client).close(CloseStatus.TOO_BIG_TO_PROCESS);
    }
    @Test void repeatedCloseIsIdempotentAndPublicCloseReleasesBackend() throws Exception {
        var bridge = bridge();
        bridge.close(CloseStatus.NORMAL);
        bridge.close(CloseStatus.NORMAL);
        verify(upstream, times(1)).sendClose(1000, "");
        verify(client, times(1)).close(CloseStatus.NORMAL);
    }
}
