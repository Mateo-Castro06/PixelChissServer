package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.RuleEngine;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import com.pixelchiss.gameservice.service.*;
import com.pixelchiss.gameservice.websocket.GameWebSocketHandler;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Produces real serialized contract examples with in-memory persistence and transport. */
class PartialReleaseContractTest extends ServiceFixture {
    final Map<String,Object> examples=new LinkedHashMap<>();
    int sequence;
    void start(int value,int homes,boolean noMovement) {
        engine=new RuleEngine(board,m->value,clock,30_000);create();
        int[] positions={5,12,25,39};
        for(int n=homes;n<4;n++) {
            if(noMovement) place("seat-0-piece-"+n,Board.FINISH);
            else placeAt("seat-0-piece-"+n,positions[n]);
        }
        repo=new MemoryGames();repo.create(game,null);
        service=new GameService(repo,engine,new JsonPayloadFingerprints(repo.json));
    }
    void send(String example,String type,String piece,Integer index,Integer value,Integer from,Integer to,String challenge) {
        var request=new GameAction(type,"g","example-"+(++sequence),service.load("g").version,
                piece,index,value,from,to,null,challenge,null,null,null);
        var ack=service.action("a",request);
        examples.put(example,Map.of("request",request,"ack",ack,
                "publication",repo.json.readTree(repo.notifications.getLast()),"sync",service.synchronization("g","a")));
    }
    void launch(String name) { send(name,"ROLL_DICE",null,null,null,null,null,null); }
    void confirm(String name) { send(name,"DICE_ANIMATION_COMPLETED",null,null,null,null,null,service.load("g").turn.rollId); }
    void move(String name,int piece,int die) {
        var m=engine.calculate(service.load("g"),"a","seat-0-piece-"+piece,die);
        send(name,"MOVE_REQUEST",m.pieceId(),die,m.dieValue(),m.from(),m.to(),null);
    }
    @Test void partialReleaseAndCrownWireExamplesMatchServerTransitions() throws Exception {
        for(int value:List.of(5,1,6)) {
            start(value,1,false);launch("one-home-"+value+"-roll");confirm("one-home-"+value+"-confirm");
            var state=service.load("g");assertArrayEquals(new boolean[]{true,false},state.turn.consumed);
            assertEquals(3,service.moves("g","a").size());
            assertTrue(service.moves("g","a").stream().noneMatch(m->m.pieceId().equals("seat-0-piece-0")));
        }
        start(5,2,false);launch("two-home-5-roll");confirm("two-home-5-confirm");
        assertTrue(service.load("g").turn.extraRollAvailable);assertTrue(service.moves("g","a").isEmpty());
        start(5,1,true);launch("no-moves-roll");confirm("no-moves-confirm");
        assertFalse(service.load("g").turn.rolled);assertNull(service.load("g").turn.movementStartedAt);
        start(5,1,false);launch("first-pair");confirm("first-confirm");move("remaining-resolved",1,1);
        launch("second-pair");confirm("second-confirm");move("second-resolved",2,2);
        launch("third-pair");confirm("third-confirm");
        assertTrue(service.load("g").turn.crownChoiceRequired);assertTrue(service.moves("g","a").isEmpty());
        send("crown-and-turn-change","CROWN_REQUEST","seat-0-piece-3",null,null,null,null,null);
        assertEquals("b",service.load("g").turn.playerId);

        var decoder=mock(JwtDecoder.class);
        when(decoder.decode("fixture-token")).thenReturn(Jwt.withTokenValue("fixture-token").header("alg","RS256")
                .subject("a").expiresAt(clock.instant().plusSeconds(900)).build());
        var handler=new GameWebSocketHandler(repo.json,decoder,service,mock(PresenceService.class),clock);
        var socket=mock(WebSocketSession.class);when(socket.getId()).thenReturn("example");when(socket.isOpen()).thenReturn(true);
        var messages=new ArrayList<String>();
        doAnswer(call->{messages.add(call.getArgument(0,WebSocketMessage.class).getPayload().toString());return null;})
                .when(socket).sendMessage(any());
        handler.afterConnectionEstablished(socket);
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"AUTH\",\"token\":\"fixture-token\"}"));
        handler.handleTextMessage(socket,new TextMessage("{\"type\":\"JOIN_GAME\",\"gameId\":\"g\"}"));
        var fourth=new GameAction("ROLL_DICE","g","fourth-roll",service.load("g").version,
                null,null,null,null,null,null,null,null,null,null);
        handler.handleTextMessage(socket,new TextMessage(repo.json.writeValueAsString(fourth)));
        var error=repo.json.readTree(messages.getLast());assertEquals("NOT_YOUR_TURN",error.path("code").asString());
        examples.put("fourth-roll-rejected",Map.of("request",fourth,"error",error));
        Files.writeString(Path.of("target/partial-release-wire-examples.json"),repo.json.writerWithDefaultPrettyPrinter().writeValueAsString(examples));
    }
}
