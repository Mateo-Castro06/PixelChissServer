package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.service.*;
import com.pixelchiss.gameservice.dto.CreateGame;
import com.pixelchiss.gameservice.websocket.GameWebSocketHandler;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.socket.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketTest extends ServiceFixture {
    @Test void authJoinRollAndPublicationReachOnlyJoinedParticipants() throws Exception {
        var decoder=mock(JwtDecoder.class);var presence=mock(PresenceService.class);
        when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg","RS256").subject("a").issuedAt(Instant.EPOCH).expiresAt(Instant.ofEpochMilli(100000)).build());
        var handler=new GameWebSocketHandler(repo.json,decoder,service,presence,clock);
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("session");when(socket.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"token\":\"valid\"}"));
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"JOIN_GAME\",\"gameId\":\"g\"}"));
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"ROLL_DICE\",\"gameId\":\"g\",\"requestId\":\"r\",\"expectedVersion\":0}"));
        handler.broadcast(repo.notifications.getLast().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var messages=org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);verify(socket,times(4)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().get(0).getPayload().toString().contains("AUTHENTICATED"));
        assertTrue(messages.getAllValues().get(3).getPayload().toString().contains("DICE_ROLLED"));
        assertTrue(messages.getAllValues().get(3).getPayload().toString().contains("[4,4]"));
        handler.afterConnectionClosed(socket,CloseStatus.NORMAL);verify(presence).remove("g","a","session");
    }
    @Test void unauthenticatedActionCannotReachEngine() throws Exception {
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("anon");when(socket.isOpen()).thenReturn(true);
        var handler=new GameWebSocketHandler(repo.json,mock(JwtDecoder.class),service,mock(PresenceService.class),clock);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"ROLL_DICE\",\"gameId\":\"g\"}"));
        verify(socket).close(CloseStatus.POLICY_VIOLATION);assertFalse(repo.find("g").turn.rolled);
    }
    @Test void connectionAndMessageArrivalCannotChooseCreatorOrRotateSeats() throws Exception {
        for(var arrival:List.of(List.of("d","a","b","c"),List.of("b","c","d","a"))) {
            game=engine.create(new CreateGame("g","l","classic",List.of(
                    new CreateGame.Participant("b",false,1),new CreateGame.Participant("d",false,3),
                    new CreateGame.Participant("a",false,0),new CreateGame.Participant("c",false,2)),"c"));
            repo=new MemoryGames();repo.create(game,"{}");service=new GameService(repo,engine,repo.json);
            var decoder=mock(JwtDecoder.class);
            var handler=new GameWebSocketHandler(repo.json,decoder,service,mock(PresenceService.class),clock);
            for(String actor:arrival) {
                when(decoder.decode(actor)).thenReturn(Jwt.withTokenValue(actor).header("alg","RS256")
                        .subject(actor).expiresAt(clock.instant().plusSeconds(3600)).build());
                var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn(actor);when(socket.isOpen()).thenReturn(true);
                handler.afterConnectionEstablished(socket);
                handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"token\":\""+actor+"\"}"));
                handler.handleTextMessage(socket,new TextMessage("{\"type\":\"JOIN_GAME\",\"gameId\":\"g\"}"));
                handler.handleTextMessage(socket,new TextMessage("{\"type\":\"SYNC_REQUEST\",\"gameId\":\"g\"}"));
                assertEquals("c",repo.find("g").turn.playerId);
            }
            for(String expected:List.of("d","a","b","c")) {
                clock.now=repo.find("g").turn.deadline;
                service.system("g",engine::tick);
                assertEquals(expected,repo.find("g").turn.playerId);
            }
        }
    }
    @Test void authenticationDeadlineAndExpiredTokenCloseTheConnection() throws Exception {
        var decoder=mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(Jwt.withTokenValue("valid").header("alg","RS256")
                .subject("a").expiresAt(Instant.ofEpochMilli(2000)).build());
        var handler=new GameWebSocketHandler(repo.json,decoder,service,mock(PresenceService.class),clock);
        var expired=mock(WebSocketSession.class);when(expired.getId()).thenReturn("expired");when(expired.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(expired);
        handler.handleTextMessage(expired,new TextMessage("{\"type\":\"AUTH\",\"token\":\"valid\"}"));
        clock.now=2000;
        handler.handleTextMessage(expired,new TextMessage("{\"type\":\"SYNC_REQUEST\",\"gameId\":\"g\"}"));
        verify(expired).close(CloseStatus.POLICY_VIOLATION);
        var late=mock(WebSocketSession.class);when(late.getId()).thenReturn("late");when(late.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(late);clock.now+=10_000;
        handler.handleTextMessage(late,new TextMessage("{\"type\":\"AUTH\",\"token\":\"valid\"}"));
        verify(late).close(CloseStatus.POLICY_VIOLATION);
        assertFalse(repo.find("g").turn.rolled);
    }
    @Test void botIdentityCannotJoinViaHumanWebSocket() throws Exception {
        game.player("a").bot=true;repo.states.put("g",repo.json.writeValueAsString(game));
        var decoder=mock(JwtDecoder.class);
        when(decoder.decode("bot")).thenReturn(Jwt.withTokenValue("bot").header("alg","RS256").subject("a")
                .expiresAt(clock.instant().plusSeconds(900)).build());
        var presence=mock(PresenceService.class);
        var handler=new GameWebSocketHandler(repo.json,decoder,service,presence,clock);
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("bot");when(socket.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"token\":\"bot\"}"));
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"JOIN_GAME\",\"gameId\":\"g\"}"));
        var messages=org.mockito.ArgumentCaptor.forClass(WebSocketMessage.class);verify(socket,times(2)).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().getLast().getPayload().toString().contains("BOT_IDENTITY"));
        verifyNoInteractions(presence);
    }
}
