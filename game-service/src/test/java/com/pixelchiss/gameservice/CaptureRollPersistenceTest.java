package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import com.pixelchiss.gameservice.service.GameService;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRollPersistenceTest extends ServiceFixture {
    void prepare() {
        int[] draws={4,3};int[] index={0};
        engine=new RuleEngine(board,m->draws[index[0]++%2],clock,30_000);
        placeAt("seat-0-piece-0",6);placeAt("seat-0-piece-1",20);
        place("seat-0-piece-2",Board.FINISH);place("seat-0-piece-3",Board.FINISH);
        placeAt("seat-1-piece-0",10);repo.states.put("g",repo.json.writeValueAsString(game));restart();
        send("ROLL_DICE",null,null,null,null,null,null);
        send("DICE_ANIMATION_COMPLETED",null,null,null,null,null,service.load("g").turn.rollId);
    }
    void restart() {service=new GameService(repo,engine,new JsonPayloadFingerprints(repo.json));}
    GameAction request(String type,String piece,Integer die,Integer value,Integer from,Integer to,String challenge) {
        return new GameAction(type,"g",UUID.randomUUID().toString(),service.load("g").version,
                piece,die,value,from,to,null,challenge,null,null,null);
    }
    GameAction send(String type,String piece,Integer die,Integer value,Integer from,Integer to,String challenge) {
        var request=request(type,piece,die,value,from,to,challenge);service.action("a",request);return request;
    }
    @Test void reconnectAndFreshServiceKeepLockDeadlineDiceAndCaptureReceipt() {
        prepare();long deadline=service.load("g").turn.deadline;String roll=service.load("g").turn.rollId;
        var capture=send("MOVE_REQUEST","seat-0-piece-0",0,4,6,10,null);restart();
        assertTrue(service.action("a",capture).duplicate());
        assertEquals(Set.of("seat-0-piece-0"),service.load("g").turn.captureLockedPieceIds);
        assertArrayEquals(new boolean[]{true,false},service.load("g").turn.consumed);
        service.system("g",g->{g.player("a").disconnectedAt=clock.now;return List.of(RuleEngine.Event.of("DISCONNECTED"));});
        clock.now+=1_000;restart();service.reconnect("g","a");var state=service.load("g");
        assertEquals(deadline,state.turn.deadline);assertEquals(roll,state.turn.rollId);
        var sync=service.synchronization("g","a");assertFalse(sync.legalMoves().isEmpty());
        assertTrue(sync.legalMoves().stream().allMatch(m->m.pieceId().equals("seat-0-piece-1")&&m.dieIndex()==1));
        var forged=request("MOVE_REQUEST","seat-0-piece-0",1,3,10,13,null);String before=repo.states.get("g");
        assertEquals("PIECE_CAPTURE_LOCKED",assertThrows(GameException.class,()->service.action("a",forged)).code());
        assertEquals(before,repo.states.get("g"));
        assertEquals("ALREADY_ROLLED",assertThrows(GameException.class,()->send("ROLL_DICE",null,null,null,null,null,null)).code());
        assertEquals(Set.of("seat-0-piece-0"),service.load("g").turn.captureLockedPieceIds);
        var move=send("MOVE_REQUEST","seat-0-piece-1",1,3,20,23,null);long version=service.load("g").version;restart();
        assertTrue(service.action("a",move).duplicate());assertTrue(service.action("a",capture).duplicate());
        assertEquals(version,service.load("g").version);assertEquals("HOME",service.load("g").piece("seat-1-piece-0").status);
        assertEquals("b",service.load("g").turn.playerId);assertTrue(service.load("g").turn.captureLockedPieceIds.isEmpty());
    }
    @Test void racingCaptureRequestsPersistOneCaptureOneLockAndOneDieConsumption() throws Exception {
        prepare();var a=request("MOVE_REQUEST","seat-0-piece-0",0,4,6,10,null);
        var b=request("MOVE_REQUEST","seat-0-piece-0",0,4,6,10,null);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->attempt(gate,a));var second=pool.submit(()->attempt(gate,b));
            gate.countDown();assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(a.expectedVersion()+1,service.load("g").version);
        assertArrayEquals(new boolean[]{true,false},service.load("g").turn.consumed);
        assertEquals(Set.of("seat-0-piece-0"),service.load("g").turn.captureLockedPieceIds);
        assertEquals(1,repo.notifications.stream().map(repo.json::readTree)
                .flatMap(n->java.util.stream.StreamSupport.stream(n.path("events").spliterator(),false))
                .filter(n->n.path("type").asString().equals("PIECE_CAPTURED")).count());
    }
    boolean attempt(CountDownLatch gate,GameAction action) throws Exception {
        gate.await();try{service.action("a",action);return true;}catch(GameException rejected){return false;}
    }
    @Test void recoveryOfExpiredRemainingDieClosesOnceAndDoesNotRestartClock() {
        prepare();send("MOVE_REQUEST","seat-0-piece-0",0,4,6,10,null);long deadline=service.load("g").turn.deadline;
        clock.now=deadline;restart();assertEquals(deadline,service.load("g").turn.deadline);
        assertTrue(service.system("g",engine::tick));assertFalse(service.system("g",engine::tick));
        var state=service.load("g");assertEquals("b",state.turn.playerId);assertNull(state.turn.movementStartedAt);
        assertEquals(10,state.piece("seat-0-piece-0").position);assertEquals(20,state.piece("seat-0-piece-1").position);
        assertTrue(state.turn.captureLockedPieceIds.isEmpty());
    }
    @Test void oldAggregatesWithoutCaptureMarkerRemainReadableWithoutGuessingHistory() {
        prepare();var tree=repo.json.readTree(repo.states.get("g"));
        ((tools.jackson.databind.node.ObjectNode)tree.path("turn")).remove("captureLockedPieceIds");
        repo.states.put("g",tree.toString());restart();
        assertTrue(service.load("g").turn.captureLockedPieceIds.isEmpty());
        assertEquals("v4-100",service.load("g").boardLayout);
        send("MOVE_REQUEST","seat-0-piece-0",0,4,6,10,null);
        assertEquals(Set.of("seat-0-piece-0"),service.load("g").turn.captureLockedPieceIds);
    }
    @Test void onlyActiveCapturerDiscardsOtherDieAndPublishesTurnChangeWithoutTimeout() throws Exception {
        prepare();service.system("g",g->{
            var piece=g.piece("seat-0-piece-1");piece.status="CROWNED";piece.progress=Board.FINISH;
            piece.position=board.position(0,1,Board.FINISH);return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        var before=service.load("g");var capture=send("MOVE_REQUEST","seat-0-piece-0",0,4,6,10,null);
        var publication=repo.json.readTree(repo.notifications.getLast());var state=service.load("g");
        assertEquals(10,state.piece("seat-0-piece-0").position);assertEquals("b",state.turn.playerId);
        assertNull(state.turn.movementStartedAt);assertNull(state.turn.rollId);
        var events=new ArrayList<String>();publication.path("events").forEach(e->events.add(e.path("type").asString()));
        assertEquals(List.of("PIECE_CAPTURED","PIECE_MOVED","TURN_CHANGED"),events);
        assertTrue(service.moves("g","a").isEmpty());assertFalse(service.system("g",engine::tick));
        assertTrue(service.action("a",capture).duplicate());
        Files.writeString(Path.of("target/capture-no-moves.json"),repo.json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of("before",before,"request",capture,"publication",publication)));
    }
}
