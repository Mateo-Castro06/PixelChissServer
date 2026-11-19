package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.service.GameService;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PairsPersistenceTest extends ServiceFixture {
    GameAction command(String type, String challenge) {
        return new GameAction(type,"g",UUID.randomUUID().toString(),service.load("g").version,
                null,null,null,null,null,null,challenge,null,null,null);
    }
    void freshService() { service=new GameService(repo,engine,new JsonPayloadFingerprints(repo.json)); }
    @Test void releaseReceiptsCountersAndExtraRollSurviveFreshServicesAndReconnect() {
        engine=new RuleEngine(board,m->2,clock,30_000);freshService();
        var roll=command("ROLL_DICE",null);service.action("a",roll);
        freshService();var waiting=service.load("g");
        assertEquals(1,waiting.turn.consecutiveDoubles);assertTrue(waiting.turn.homeRelease);
        assertTrue(service.action("a",roll).duplicate());
        service.system("g",g->{g.player("a").disconnectedAt=clock.now;return List.of(RuleEngine.Event.of("DISCONNECTED"));});
        service.reconnect("g","a");
        var ack=command("DICE_ANIMATION_COMPLETED",waiting.turn.rollId);service.action("a",ack);
        freshService();assertTrue(service.action("a",ack).duplicate());
        assertTrue(service.load("g").turn.extraRollAvailable);
        assertEquals(2,service.load("g").pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("BOARD")).count());
        service.action("a",command("ROLL_DICE",null));
        assertEquals(2,service.load("g").turn.consecutiveDoubles);
        assertNotEquals(waiting.turn.rollId,service.load("g").turn.rollId);
        assertEquals(4,service.load("g").pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("BOARD")).count());
    }
    void pausedTrivia() {
        engine=new TriviaRules(board,m->1,clock,30_000,new SeedCatalog(),15_000,15_000);
        activateHomeForMovement();placeAt("seat-0-piece-0",7);
        game.turn.rolled=true;game.turn.dice=new int[]{1,2};game.turn.rollId="paused-roll";
        game.turn.animationStatus="COMPLETED";game.turn.movementStartedAt=clock.now;
        game.turn.deadline=clock.now+30_000;
        repo.states.put("g",repo.json.writeValueAsString(game));freshService();clock.now+=12_000;
        var m=service.moves("g","a").stream().filter(v->v.pieceId().equals("seat-0-piece-0")&&v.dieIndex()==0).findFirst().orElseThrow();
        service.action("a",new GameAction("MOVE_REQUEST","g","land",0L,m.pieceId(),0,m.dieValue(),m.from(),m.to(),null,null,null,null,null));
    }
    @Test void restartAndReconnectKeepTriviaDeadlineAndPausedMovementThenResumeOnce() {
        pausedTrivia();long deadline=service.load("g").trivia.deadline;
        clock.now+=10_000;freshService();
        service.system("g",g->{g.player("a").disconnectedAt=clock.now;return List.of(RuleEngine.Event.of("DISCONNECTED"));});
        service.reconnect("g","a");var state=service.load("g");
        assertEquals(deadline,state.trivia.deadline);assertEquals(18_000,state.turn.movementRemainingMillis);
        var answer=new GameAction("TRIVIA_ANSWER","g","answer",state.version,null,null,null,null,null,null,state.trivia.id,0,null,null);
        service.action("a",answer);long resumed=service.load("g").turn.deadline;
        assertEquals(clock.now+18_000,resumed);clock.now+=1000;freshService();
        assertTrue(service.action("a",answer).duplicate());assertEquals(resumed,service.load("g").turn.deadline);
    }
    @Test void competingTriviaTimeoutsCommitOneResolution() throws Exception {
        pausedTrivia();clock.now=service.load("g").trivia.deadline;
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return service.system("g",engine::tick);});
            var b=pool.submit(()->{gate.await();return service.system("g",engine::tick);});
            gate.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertNull(service.load("g").trivia);assertEquals(clock.now+18_000,service.load("g").turn.deadline);
    }
}
