package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.service.GameService;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnDeadlineServiceTest extends ServiceFixture {
    GameAction command(String type, GameState state, String challenge) {
        return new GameAction(type,"g",UUID.randomUUID().toString(),state.version,
                null,null,null,null,null,null,challenge,null,null,null);
    }
    void prepare() {
        engine=new RuleEngine(board,m->4,clock,30_000);
        place("seat-0-piece-0",10);
        activateHomeForMovement();
        repo.states.put("g",repo.json.writeValueAsString(game));
        service=new GameService(repo,engine,new JsonPayloadFingerprints(repo.json));
        service.action("a",command("ROLL_DICE",game,null));
    }
    @Test void detachedReloadReconnectAndNewServicePreserveDeadlinesAndReceipts() {
        prepare();var waiting=service.load("g");long animation=waiting.turn.animationDeadline;
        var fresh=new GameService(repo,new RuleEngine(board,m->6,clock,30_000),new JsonPayloadFingerprints(repo.json));
        assertEquals(animation,fresh.load("g").turn.animationDeadline);assertNull(fresh.load("g").turn.movementStartedAt);
        var ack=command("DICE_ANIMATION_COMPLETED",waiting,waiting.turn.rollId);fresh.action("a",ack);
        long movement=fresh.load("g").turn.deadline;clock.now+=500;
        fresh.system("g",g->{g.player("a").disconnectedAt=clock.now;return List.of(RuleEngine.Event.of("PLAYER_DISCONNECTED"));});
        fresh.reconnect("g","a");assertEquals(movement,fresh.load("g").turn.deadline);
        assertTrue(fresh.action("a",ack).duplicate());assertEquals(movement,fresh.load("g").turn.deadline);
        clock.now=movement;assertTrue(fresh.system("g",engine::tick));assertFalse(fresh.system("g",engine::tick));
        assertEquals("b",fresh.load("g").turn.playerId);
    }
    @Test void simultaneousTimeoutsCommitOneTransitionAndLateMoveNeverPersists() throws Exception {
        prepare();var waiting=service.load("g");service.action("a",command("DICE_ANIMATION_COMPLETED",waiting,waiting.turn.rollId));
        var playable=service.load("g");var move=engine.legalMoves(playable,"a").getFirst();
        clock.now=playable.turn.deadline;
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var a=pool.submit(()->{gate.await();return service.system("g",engine::tick);});
            var b=pool.submit(()->{gate.await();return service.system("g",engine::tick);});
            var c=pool.submit(()->{gate.await();try {
                service.action("a",new GameAction("MOVE_REQUEST","g","late",playable.version,
                        move.pieceId(),move.dieIndex(),move.dieValue(),move.from(),move.to(),null,null,null,null,null));return true;
            }catch(GameException expected){return false;}});
            gate.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertFalse(c.get());
        }
        var closed=service.load("g");assertEquals(playable.version+1,closed.version);
        assertEquals("b",closed.turn.playerId);assertEquals(10,closed.piece("seat-0-piece-0").progress);
    }
}
