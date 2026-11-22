package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import com.pixelchiss.gameservice.service.GameService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartialReleasePersistenceTest extends ServiceFixture {
    String own(int n) { return "seat-0-piece-"+n; }
    GameAction command(String type, String piece, Integer index, Integer value, Integer from, Integer to, String challenge) {
        return new GameAction(type,"g",UUID.randomUUID().toString(),service.load("g").version,
                piece,index,value,from,to,null,challenge,null,null,null);
    }
    void restart() { service=new GameService(repo,engine,new JsonPayloadFingerprints(repo.json)); }
    void prepare(boolean third) {
        engine=new RuleEngine(board,m->5,clock,30_000);
        for(int n=1;n<4;n++) placeAt(own(n),12+n*10);
        if(third) game.turn.consecutiveDoubles=2;
        repo.states.put("g",repo.json.writeValueAsString(game)); restart();
    }
    GameAction launch() {
        var request=command("ROLL_DICE",null,null,null,null,null,null);service.action("a",request);return request;
    }
    GameAction confirm() {
        var request=command("DICE_ANIMATION_COMPLETED",null,null,null,null,null,service.load("g").turn.rollId);
        service.action("a",request);return request;
    }
    @Test void pendingDieExclusionRollIdAndReceiptsSurviveRestartAndReconnect() {
        prepare(false);var roll=launch();var before=service.load("g");restart();
        assertTrue(service.action("a",roll).duplicate());
        assertEquals(Set.of(own(0)),service.load("g").turn.releasedPieceIds);
        assertEquals(before.turn.rollId,service.load("g").turn.rollId);
        assertArrayEquals(new boolean[]{true,false},service.load("g").turn.consumed);
        var ack=confirm();long deadline=service.load("g").turn.deadline;
        service.system("g",g->{g.player("a").disconnectedAt=clock.now;return List.of(RuleEngine.Event.of("DISCONNECTED"));});
        restart();service.reconnect("g","a");
        assertTrue(service.action("a",ack).duplicate());assertEquals(deadline,service.load("g").turn.deadline);
        var sync=service.synchronization("g","a");
        assertTrue(sync.legalMoves().stream().noneMatch(m->m.pieceId().equals(own(0))));
        assertTrue(sync.legalMoves().stream().allMatch(m->m.dieIndex()==1&&m.dieValue()==5));
        var forged=command("MOVE_REQUEST",own(0),1,5,5,10,null);
        String unchanged=repo.states.get("g");
        assertEquals("PIECE_RELEASED_THIS_ROLL",assertThrows(GameException.class,()->service.action("a",forged)).code());
        assertEquals(unchanged,repo.states.get("g"));
        var move=sync.legalMoves().getFirst();
        var request=command("MOVE_REQUEST",move.pieceId(),1,5,move.from(),move.to(),null);
        service.action("a",request);long version=service.load("g").version;restart();
        assertTrue(service.action("a",request).duplicate());assertEquals(version,service.load("g").version);
        assertEquals(1,service.load("g").turn.consecutiveDoubles);assertTrue(service.load("g").turn.extraRollAvailable);
        launch();confirm();
        assertTrue(service.moves("g","a").stream().anyMatch(m->m.pieceId().equals(own(0))));
        assertEquals("STALE_VERSION",assertThrows(GameException.class,()->service.action("a",forged)).code());
    }
    @Test void competingRemainingMovesCommitAtMostOnce() throws Exception {
        prepare(false);launch();confirm();var move=service.moves("g","a").getFirst();
        var a=command("MOVE_REQUEST",move.pieceId(),1,5,move.from(),move.to(),null);
        var b=command("MOVE_REQUEST",move.pieceId(),1,5,move.from(),move.to(),null);
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->attempt(gate,a));var second=pool.submit(()->attempt(gate,b));
            gate.countDown();assertNotEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(a.expectedVersion()+1,service.load("g").version);
        assertEquals(move.to(),service.load("g").piece(move.pieceId()).position);
    }
    boolean attempt(CountDownLatch gate, GameAction request) throws Exception {
        gate.await();try{service.action("a",request);return true;}catch(GameException rejected){return false;}
    }
    @Test void thirdPairRewardAndTurnClosurePersistOnceAndRejectLateRolls() {
        prepare(true);var roll=launch();confirm();restart();
        assertTrue(service.action("a",roll).duplicate());
        var crown=command("CROWN_REQUEST",own(1),null,null,null,null,null);
        service.action("a",crown);var closed=service.load("g");restart();
        assertTrue(service.action("a",crown).duplicate());
        assertEquals("CROWNED",closed.piece(own(1)).status);assertEquals("b",closed.turn.playerId);
        assertEquals(0,closed.turn.consecutiveDoubles);assertFalse(closed.turn.thirdDoubleReward);
        assertNull(closed.turn.rollId);assertNull(closed.turn.movementStartedAt);
        assertNull(closed.turn.movementRemainingMillis);assertNull(closed.turn.pausedMovementRollId);
        assertEquals("NOT_YOUR_TURN",assertThrows(GameException.class,this::launch).code());
        assertFalse(service.system("g",engine::tick));
    }
    @Test void legacyReleaseWithoutNewFieldKeepsConsumedDiceAndNeedsNoMigration() {
        prepare(false);launch();var current=service.load("g");
        current.turn.consumed=new boolean[]{true,true};
        var tree=repo.json.readTree(repo.json.writeValueAsString(current));
        ((tools.jackson.databind.node.ObjectNode)tree.path("turn")).remove("releasedPieceIds");
        repo.states.put("g",tree.toString());restart();
        assertTrue(service.load("g").turn.releasedPieceIds.isEmpty());confirm();
        assertTrue(service.load("g").turn.extraRollAvailable);assertTrue(service.moves("g","a").isEmpty());
        assertArrayEquals(new boolean[]{true,true},service.load("g").turn.consumed);
    }
    @Test void pendingRemainingDieTimeoutRecoversOnceWithoutExtendingDeadline() {
        prepare(false);launch();confirm();long deadline=service.load("g").turn.deadline;
        clock.now=deadline+1;restart();assertEquals(deadline,service.load("g").turn.deadline);
        assertTrue(service.system("g",engine::tick));assertFalse(service.system("g",engine::tick));
        assertEquals("b",service.load("g").turn.playerId);assertEquals("BOARD",service.load("g").piece(own(0)).status);
    }
}
