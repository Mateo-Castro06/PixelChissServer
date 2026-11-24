package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.bot.*;
import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import com.pixelchiss.gameservice.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticRollResolutionTest extends EngineFixture {
    MemoryGames repository;
    GameService service;
    String own(int n) { return "seat-0-piece-"+n; }
    void prepare(String map,int first,int second) {
        int[] dice={first,second};int[] index={0};
        engine=new RuleEngine(board,max->dice[index[0]++%2],clock,30_000);
        game=engine.create(new GameSetup("g","l",map,List.of(
                new GameSetup.Participant("a",false,0),new GameSetup.Participant("b",false,1)),"a"));
        placeAt(own(0),6);place(own(1),75);place(own(2),76);place(own(3),76);
        repository=new MemoryGames();persist();restart();
    }
    void persist() { repository.states.put("g",repository.json.writeValueAsString(game)); }
    void restart() { service=new GameService(repository,engine,new JsonPayloadFingerprints(repository.json),clock,BotPacing.defaults()); }
    GameAction command(String type,String piece,Integer die,Integer value,Integer from,Integer to,String challenge) {
        var state=service.load("g");
        return new GameAction(type,"g",UUID.randomUUID().toString(),state.version,piece,die,value,from,to,null,challenge,null,null,null);
    }
    GameAction moveRequest(String piece,int die) {
        var m=engine.calculate(service.load("g"),"a",piece,die);
        return command("MOVE_REQUEST",piece,die,m.dieValue(),m.from(),m.to(),null);
    }
    void rollAndConfirm() {
        service.action("a",command("ROLL_DICE",null,null,null,null,null,null));
        clock.now+=1900;
        service.action("a",command("DICE_ANIMATION_COMPLETED",null,null,null,null,null,service.load("g").turn.rollId));
    }
    List<String> lastEvents() {
        var events=repository.json.readTree(repository.notifications.getLast()).path("events");
        var types=new ArrayList<String>();events.forEach(e->types.add(e.path("type").asString()));return types;
    }
    @ParameterizedTest @CsvSource({"classic,0","classic,1","science,0","science,1"})
    void captureAndOvershootingRemainderCloseInTheSameCommit(String map,int die) {
        prepare(map,4,3);placeAt("seat-1-piece-0",6+(die==0?4:3));persist();rollAndConfirm();
        long now=clock.now;var request=moveRequest(own(0),die);
        service.action("a",request);
        var closed=service.load("g");assertEquals("b",closed.turn.playerId);
        assertEquals(now,clock.now);assertNull(closed.turn.movementStartedAt);assertNull(closed.turn.rollId);
        assertEquals(0,closed.turn.dice.length);assertEquals(75,closed.piece(own(1)).progress);
        assertEquals(List.of("PIECE_CAPTURED","PIECE_MOVED","TURN_CHANGED"),lastEvents());
        long version=closed.version;int publications=repository.notifications.size();restart();
        assertTrue(service.action("a",request).duplicate());
        assertEquals(version,service.load("g").version);assertEquals(publications,repository.notifications.size());
        assertTrue(service.synchronization("g","a").legalMoves().isEmpty());
        assertFalse(service.system("g",engine::tick));
        var stale=new GameAction(request.type(),request.gameId(),"late",request.expectedVersion(),request.pieceId(),
                request.dieIndex(),request.dieValue(),request.fromPosition(),request.targetPosition(),null,null,null,null,null);
        assertEquals("STALE_VERSION",assertThrows(GameException.class,()->service.action("a",stale)).code());
        System.out.println("AUTO_CLOSE map="+map+" capturedDie="+request.dieValue()+" logicalWait=0ms version="+version);
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void anotherPieceWithExactThreeKeepsTheRollOpen(String map) {
        prepare(map,4,3);place(own(1),73);placeAt("seat-1-piece-0",10);persist();rollAndConfirm();
        long deadline=service.load("g").turn.deadline;
        service.action("a",moveRequest(own(0),0));var state=service.load("g");
        assertEquals("a",state.turn.playerId);assertEquals(Set.of(own(0)),state.turn.captureLockedPieceIds);
        assertArrayEquals(new boolean[]{true,false},state.turn.consumed);assertEquals(deadline,state.turn.deadline);
        var legal=service.moves("g","a");assertEquals(1,legal.size());assertEquals(own(1),legal.getFirst().pieceId());
        assertTrue(legal.getFirst().crown());service.action("a",moveRequest(own(1),1));
        assertEquals("b",service.load("g").turn.playerId);
    }
    @ParameterizedTest @CsvSource({"classic,false","science,false","classic,true","science,true"})
    void impossibleDiceNeverPublishAMovementTimer(String map,boolean doubles) {
        prepare(map,3,doubles?3:4);
        for(int n=0;n<4;n++) place(own(n),75);
        persist();rollAndConfirm();
        assertFalse(lastEvents().contains("MOVEMENT_TIMER_STARTED"));
        assertNull(service.load("g").turn.movementStartedAt);
        if(doubles) {
            assertEquals("a",service.load("g").turn.playerId);
            assertTrue(service.load("g").turn.extraRollAvailable);
            assertArrayEquals(new boolean[]{true,true},service.load("g").turn.consumed);
            assertEquals(List.of("ROLL_AVAILABLE"),lastEvents());
        } else assertEquals(List.of("TURN_CHANGED"),lastEvents());
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void oneLegalDieMustBeUsedBeforeDiscardingTheOther(String map) {
        prepare(map,3,4);place(own(0),73);game.piece(own(1)).home();persist();rollAndConfirm();
        assertEquals(List.of("MOVEMENT_TIMER_STARTED"),lastEvents());
        assertTrue(service.moves("g","a").stream().allMatch(m->m.dieIndex()==0));
        service.action("a",moveRequest(own(0),0));
        assertEquals("CROWNED",service.load("g").piece(own(0)).status);
        assertEquals("b",service.load("g").turn.playerId);
    }
    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void recoveredImpossibleRemainderResolvesBeforeDeadlineOnce(boolean triviaEngine,boolean doubles) {
        prepare("classic",4,doubles?4:3);
        if(triviaEngine) engine=new TriviaRules(board,m->1,clock,30_000,new SeedCatalog(),15_000,15_000);
        game.turn.rolled=true;game.turn.dice=new int[]{4,doubles?4:3};game.turn.doubles=doubles;
        game.turn.consumed=new boolean[]{true,false};game.turn.animationStatus="COMPLETED";
        game.turn.rollId="recovered";game.turn.movementStartedAt=clock.now;game.turn.deadline=clock.now+30_000;
        game.turn.captureSatisfied=true;game.turn.captureLockedPieceIds.add(own(0));
        persist();restart();assertTrue(service.moves("g","a").isEmpty());
        assertTrue(service.system("g",engine::tick));
        assertFalse(service.system("g",engine::tick));
        assertFalse(lastEvents().contains("TURN_EXPIRED"));
        assertEquals(doubles?List.of("ROLL_AVAILABLE"):List.of("TURN_CHANGED"),lastEvents());
        assertNull(service.load("g").turn.movementStartedAt);
    }
    @Test void recoveryDoesNotCloseWhileAnotherPieceHasALegalMove() {
        prepare("classic",4,3);rollAndConfirm();
        assertFalse(service.system("g",engine::tick));assertEquals("a",service.load("g").turn.playerId);
    }
    @ParameterizedTest @CsvSource({"WAITING","TRIVIA","CARD","CROWN"})
    void recoveryPreservesPendingObligations(String pending) {
        prepare("science",4,3);
        engine=new TriviaRules(board,m->1,clock,30_000,new SeedCatalog(),15_000,15_000);
        for(int n=0;n<4;n++) place(own(n),75);
        game.turn.rolled=true;game.turn.dice=new int[]{4,3};game.turn.consumed=new boolean[]{false,false};
        game.turn.animationStatus="COMPLETED";game.turn.deadline=clock.now+30_000;
        switch(pending) {
            case "WAITING" -> game.turn.animationStatus="WAITING";
            case "TRIVIA" -> {
                game.trivia=new GameState.PendingTrivia();game.trivia.deadline=clock.now+15_000;
                game.turn.rollId="paused";game.turn.pausedMovementRollId="paused";game.turn.movementRemainingMillis=20_000L;
            }
            case "CARD" -> {game.card=new GameState.PendingCard();game.card.deadline=clock.now+15_000;}
            case "CROWN" -> game.turn.crownChoiceRequired=true;
        }
        persist();restart();String before=repository.states.get("g");
        assertFalse(service.system("g",engine::tick));assertEquals(before,repository.states.get("g"));
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void fullProtectedDestinationDiscardsRemainderAfterTheLegalDie(String map) {
        prepare(map,3,6);placeAt(own(0),13);place(own(1),76);
        for(int n=0;n<4;n++) placeAt("seat-1-piece-"+n,22);
        persist();rollAndConfirm();assertFalse(service.moves("g","a").isEmpty());
        service.action("a",moveRequest(own(0),0));
        assertEquals(16,service.load("g").piece(own(0)).position);
        assertEquals("b",service.load("g").turn.playerId);
        assertEquals(List.of("PIECE_MOVED","TURN_CHANGED"),lastEvents());
    }
    @Test void botWithImpossibleDiceClosesThroughItsRollWithoutAnExtraActionOrPause() {
        prepare("classic",4,3);game.player("a").bot=true;
        for(int n=0;n<4;n++) place(own(n),75);
        persist();
        var runner=new BotRunner(repository,service,new BotPolicy(engine,new SeedCatalog(),()->0.0));
        runner.step();assertEquals("b",service.load("g").turn.playerId);
        assertEquals(1,service.load("g").version);assertEquals(1,repository.notifications.size());
        assertEquals(clock.now+2100,repository.ready.get("g")); // Only the dice animation is budgeted.
        runner.step();assertEquals(1,service.load("g").version);
    }
    @Test void schedulerRecoversNoMovesOnceWithoutDiscardActionFromClient() {
        prepare("classic",4,3);
        game.turn.rolled=true;game.turn.dice=new int[]{4,3};game.turn.consumed=new boolean[]{true,false};
        game.turn.animationStatus="COMPLETED";game.turn.deadline=clock.now+30_000;
        game.turn.captureSatisfied=true;game.turn.captureLockedPieceIds.add(own(0));persist();
        var presence=org.mockito.Mockito.mock(PresenceService.class);
        org.mockito.Mockito.when(presence.connected(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var scheduler=new GameScheduler(service,repository,engine,presence,clock,60_000);
        scheduler.tick();assertEquals("b",service.load("g").turn.playerId);
        assertEquals(List.of("TURN_CHANGED"),lastEvents());
        scheduler.tick();assertEquals(1,service.load("g").version);
    }
    @Test void concurrentRecoveryTicksCommitOnlyOneTurnChange() throws Exception {
        prepare("classic",4,3);
        game.turn.rolled=true;game.turn.dice=new int[]{4,3};game.turn.consumed=new boolean[]{true,false};
        game.turn.animationStatus="COMPLETED";game.turn.deadline=clock.now+30_000;
        game.turn.captureSatisfied=true;game.turn.captureLockedPieceIds.add(own(0));persist();
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> tick=()->{gate.await();return service.system("g",engine::tick);};
            var first=pool.submit(tick);var second=pool.submit(tick);gate.countDown();
            assertNotEquals(first.get(5,java.util.concurrent.TimeUnit.SECONDS),second.get(5,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1,service.load("g").version);assertEquals(1,repository.notifications.size());
        assertEquals(List.of("TURN_CHANGED"),lastEvents());
    }
}
