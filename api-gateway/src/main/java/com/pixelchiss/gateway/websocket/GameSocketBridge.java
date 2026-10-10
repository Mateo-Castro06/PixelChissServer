package com.pixelchiss.gateway.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** One downstream connection per public socket; forwards payloads without interpreting game actions. */
final class GameSocketBridge implements WebSocket.Listener {
    static final int SEND_TIMEOUT_MILLIS = 3000;
    static final int MAX_RESPONSE_BYTES = 65_536;
    private final StringBuilder text = new StringBuilder();
    private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
    private volatile WebSocket upstream;
    private WebSocketSession client;
    private boolean closed;
    private CloseStatus closeStatus = CloseStatus.SERVER_ERROR;
    private volatile long lastPongAt = System.currentTimeMillis();

    @Override public void onOpen(WebSocket socket) {
        upstream = socket;
        synchronized (this) {
            if (closed) socket.abort();
            else socket.request(1);
        }
    }

    synchronized void attach(WebSocketSession session) throws IOException {
        session.setTextMessageSizeLimit(16_384);
        session.setBinaryMessageSizeLimit(16_384);
        client = new ConcurrentWebSocketSessionDecorator(session, SEND_TIMEOUT_MILLIS, MAX_RESPONSE_BYTES);
        lastPongAt = System.currentTimeMillis();
        if (closed) client.close(closeStatus);
    }

    void forward(WebSocketMessage<?> message) throws Exception {
        if (message instanceof TextMessage value) {
            upstream.sendText(value.getPayload(), true).get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } else if (message instanceof BinaryMessage value) {
            upstream.sendBinary(value.getPayload(), true).get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        text.append(data);
        if (text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            close(CloseStatus.TOO_BIG_TO_PROCESS);
            return null;
        }
        if (last) {
            send(new TextMessage(text.toString()));
            text.setLength(0);
        }
        socket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
        if (binary.size() + data.remaining() > MAX_RESPONSE_BYTES) {
            close(CloseStatus.TOO_BIG_TO_PROCESS);
            return null;
        }
        byte[] part = new byte[data.remaining()];
        data.get(part);
        binary.writeBytes(part);
        if (last) {
            send(new BinaryMessage(binary.toByteArray()));
            binary.reset();
        }
        socket.request(1);
        return null;
    }

    private synchronized void send(WebSocketMessage<?> message) {
        if (closed) return;
        try {
            if (client == null) throw new IOException("Public socket not established");
            client.sendMessage(message);
        } catch (IOException | RuntimeException error) {
            close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
        // Do not log or forward arbitrary backend close reasons.
        close(new CloseStatus(code));
        return null;
    }

    @Override public void onError(WebSocket socket, Throwable error) {
        close(CloseStatus.SERVER_ERROR);
    }

    void pongReceived() {
        lastPongAt = System.currentTimeMillis();
    }

    synchronized void heartbeat(long now, long pongTimeoutMillis) {
        if (closed || client == null) return;
        if (now - lastPongAt > pongTimeoutMillis) {
            close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        send(new PingMessage());
    }

    synchronized void close(CloseStatus status) {
        if (closed) return;
        closed = true;
        closeStatus = status;
        if (client != null) {
            try { client.close(status); } catch (IOException ignored) { }
        }
        WebSocket socket = upstream;
        if (socket != null) {
            int code = status.getCode();
            if (code == 1005 || code == 1006 || code == 1015) code = 1011;
            socket.sendClose(code, "").orTimeout(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .whenComplete((result, error) -> { if (error != null) socket.abort(); });
        }
    }
}
