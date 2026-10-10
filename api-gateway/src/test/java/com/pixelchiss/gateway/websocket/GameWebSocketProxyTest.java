package com.pixelchiss.gateway.websocket;

import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameWebSocketProxyTest {
    @Test void rejectsAllQueryParametersBeforeDiscovery() {
        var discovery = mock(LoadBalancerClient.class);
        var request = mock(ServerHttpRequest.class);
        var response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/api/game/ws?token=fixture"));
        var proxy = new GameWebSocketProxy(discovery, 30_000);
        try {
            assertFalse(proxy.beforeHandshake(request, response, proxy, new HashMap<>()));
            verify(response).setStatusCode(HttpStatus.BAD_REQUEST);
            verifyNoInteractions(discovery);
        } finally { proxy.shutdown(); }
    }
    @Test void noDiscoverableGameFailsHandshakeWithoutAnonymousBackendConnection() {
        var discovery = mock(LoadBalancerClient.class);
        var request = mock(ServerHttpRequest.class);
        var response = mock(ServerHttpResponse.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/api/game/ws"));
        var proxy = new GameWebSocketProxy(discovery, 30_000);
        try {
            assertFalse(proxy.beforeHandshake(request, response, proxy, new HashMap<>()));
            verify(discovery).choose("GAME-SERVICE");
            verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        } finally { proxy.shutdown(); }
    }
}
