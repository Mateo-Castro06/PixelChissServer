package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.bot.*;
import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import com.pixelchiss.gameservice.service.GameService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class BotPacingTest extends ServiceFixture {
    final BotPacing pacing = BotPacing.defaults();
    BotPolicy policy;
    @BeforeEach void bots() {
        game.player("a").bot=true;
        activateHomeForMovement();
        repo.states.put(game.id,repo.json.writeValueAsString(game));
        service = freshService();
        policy=new BotPolicy(engine,new SeedCatalog(),()->0.0);
    }
    GameService freshService() {
        return new GameService(repo,engine,new JsonPayloadFingerprints(repo.json),clock,pacing);
    }
    GameAction next() { return policy.next(service.load("g")); }
    void execute() { var g=service.load("g"); service.botAction(g.turn.playerId,policy.next(g)); }
    @ParameterizedTest @CsvSource({"ROLL_DICE,2100","MOVE_REQUEST,1300","CROWN_REQUEST,1300",
            "TRIVIA_ANSWER,1500","END_TURN,500","CARD_TARGET,800"})
    void configuredActionBudgets(String action,long delay) { assertEquals(delay,pacing.delay(action,List.of())); }
    @Test void rollThenTwoMovesRespectBoundariesAndDuplicatesDoNotExtendPause() {
        GameAction roll=next(); service.botAction("a",roll);
        assertEquals(3100,repo.ready.get("g"));
        assertTrue(service.botAction("a",roll).duplicate());
        assertEquals(3100,repo.ready.get("g"));
        GameAction move=next();
        clock.now=3099; assertThrows(GameException.class,()->service.botAction("a",move));
        assertEquals(1,service.load("g").version);
        clock.now=3100; service.botAction("a",move);
        long ready=repo.ready.get("g"); assertEquals(4400,ready);
        assertThrows(GameException.class,this::execute);
        clock.now=ready; execute(); assertEquals(3,service.load("g").version);
    }
    @Test void restartAndStaleActionsDoNotBypassOrExtendPause() {
        var stale=next(); execute(); service=freshService();
        assertFalse(service.botReady("g"));
        clock.now=repo.ready.get("g");
        assertThrows(GameException.class,()->service.botAction("a",stale));
        assertEquals(1,service.load("g").version);
        execute(); assertEquals(2,service.load("g").version);
    }
    @Test void failedPersistenceDoesNotReserveOrPublish() {
        repo.states.remove("g");
        assertFalse(repo.commitBot(game,0,null,2100,clock.now));
        assertFalse(repo.ready.containsKey("g"));
    }
    @Test void twoSchedulersPublishOneAction() throws Exception {
        GameAction action=next();
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> task=()-> {start.await();try {return !freshService().botAction("a",action).duplicate();}
                catch(GameException e) { return false; }};
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertEquals(1,(first.get()?1:0)+(second.get()?1:0));
        }
        assertEquals(1,service.load("g").version);
        assertEquals(2,repo.notifications.size());
    }
    @Test void independentGamesAndDeletedMetadataShareHashLifetime() {
        execute(); var other=repo.find("g"); other.id="other"; other.version=0;repo.create(other,null);
        assertFalse(service.botReady("g")); assertTrue(service.botReady("other"));
        assertThrows(GameException.class,()->service.load("deleted"));
        assertTrue(service.botReady("deleted"));
    }
    @Test void releaseAndCaptureBudgetsIncludeEveryVisual() {
        var events=List.of(RuleEngine.Event.of("PIECE_RELEASED"),RuleEngine.Event.of("PIECE_RELEASED"),
                RuleEngine.Event.of("PIECE_CAPTURED"));
        assertEquals(6000,pacing.delay("ROLL_DICE",events));
        assertEquals(2600,pacing.delay("MOVE_REQUEST",List.of(RuleEngine.Event.of("PIECE_CAPTURED"),RuleEngine.Event.of("PIECE_MOVED"))));
        assertEquals(1300,pacing.delay("CARD_TARGET",List.of(RuleEngine.Event.of("PIECE_MOVED"))));
        assertEquals(1300,pacing.delay("END_TURN",List.of(RuleEngine.Event.of("PIECE_BLOWN"))));
    }
    @Test void automaticHomeReleaseWaitsForAllFourPieces() {
        engine=new RuleEngine(board,m->1,clock,30000); service=freshService();
        create(); game.player("a").bot=true; repo.states.put("g",repo.json.writeValueAsString(game));
        execute(); assertEquals(clock.now+2100+4*1300,repo.ready.get("g"));
    }
    @Test void botToHumanPreservesDiceAckAndMovementBudget() {
        engine=new RuleEngine(board,m->4,clock,30000);service=freshService();
        game.pieces.stream().filter(p->p.ownerId.equals("b")).forEach(p->place(p.id,0));
        game.turn.rolled=true; game.turn.dice=new int[]{1,2};game.turn.consumed=new boolean[]{true,true};
        repo.states.put("g",repo.json.writeValueAsString(game));
        execute(); var human=service.load("g");assertEquals("b",human.turn.playerId);
        assertNull(human.turn.movementStartedAt);
        service.action("b",command("ROLL_DICE",human,null));
        human=service.load("g"); assertEquals("WAITING",human.turn.animationStatus);
        assertNull(human.turn.movementStartedAt);
        assertEquals(clock.now+10000,human.turn.deadline);
        clock.now+=1900;
        service.action("b",command("DICE_ANIMATION_COMPLETED",human,human.turn.rollId));
        human=service.load("g");assertEquals(clock.now,human.turn.movementStartedAt);
        assertEquals(clock.now+30000,human.turn.deadline);
    }
    GameAction command(String type,GameState g,String challenge) {
        return new GameAction(type,g.id,UUID.randomUUID().toString(),g.version,null,null,null,null,null,null,challenge,null,null,null);
    }
    @ParameterizedTest @CsvSource({"2","3"})
    void consecutiveBotsInheritPreviousPlayersPause(int count) {
        for(int i=0;i<count;i++) game.players.get(i).bot=true;
        game.turn.rolled=true;game.turn.dice=new int[]{1,2};game.turn.consumed=new boolean[]{true,true};
        repo.states.put("g",repo.json.writeValueAsString(game)); execute();
        assertEquals("b",service.load("g").turn.playerId);
        assertThrows(GameException.class,this::execute);
        clock.now+=500; execute();assertEquals(2,service.load("g").version);
    }
    @Test void finishedGameClearsMetadataAndPolicyStops() {
        execute();var g=service.load("g");g.status="FINISHED";g.version++;
        assertTrue(repo.commit(g,g.version-1,null));assertFalse(repo.ready.containsKey("g"));
        assertNull(policy.next(service.load("g")));
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void lastMovePublishesTurnChangeButNextBotWaits(String map) {
        game.mapId=map;game.player("b").bot=true;
        game.turn.rolled=true;game.turn.dice=new int[]{1,2};game.turn.consumed=new boolean[]{true,false};
        repo.states.put("g",repo.json.writeValueAsString(game));
        execute();assertEquals("b",service.load("g").turn.playerId);
        assertEquals(clock.now+1300,repo.ready.get("g"));
        assertThrows(GameException.class,this::execute);
        clock.now+=1300;execute();assertEquals(2,service.load("g").version);
    }
    @Test void thirdPairCrownEndsTurnWithoutFourthRollAndNextBotWaits() {
        game.player("b").bot=true;game.turn.consecutiveDoubles=2;
        repo.states.put("g",repo.json.writeValueAsString(game));execute();
        assertTrue(service.load("g").turn.crownChoiceRequired);
        assertEquals("CROWN_REQUEST",next().type());
        assertThrows(GameException.class,this::execute);
        clock.now+=2100;execute();
        assertEquals("b",service.load("g").turn.playerId);
        assertEquals(1,service.load("g").pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("CROWNED")).count());
        assertEquals(clock.now+1300,repo.ready.get("g"));
        assertThrows(GameException.class,this::execute);
    }
    @Test void winningMovePublishesOnceAndLeavesNoPacingMetadata() {
        game.pieces.stream().filter(p->p.ownerId.equals("a")).forEach(p->place(p.id,Board.FINISH));
        place("seat-0-piece-0",Board.FINISH-1);
        game.turn.rolled=true;game.turn.dice=new int[]{1,2};game.turn.consumed=new boolean[]{false,true};
        repo.states.put("g",repo.json.writeValueAsString(game));execute();
        assertEquals("FINISHED",service.load("g").status);
        assertEquals("a",service.load("g").winnerId);
        assertFalse(repo.ready.containsKey("g"));assertNull(next());
    }
    @Test void runnerChecksReadinessWithoutBlockingOtherGames() {
        var runner=new BotRunner(repo,service,policy);runner.step();
        assertEquals(1,service.load("g").version);runner.step();assertEquals(1,service.load("g").version);
        clock.now+=2100;runner.step();assertEquals(2,service.load("g").version);
    }
    @Test void threeBotsThenHumanWithNoLegalMovementKeepPerRollBudget() {
        int[] die={0};engine=new RuleEngine(board,m->++die[0]%2+1,clock,30000);service=freshService();
        game=engine.create(new GameSetup("g","l","classic",List.of(
                new GameSetup.Participant("a",true,0),new GameSetup.Participant("b",true,1),
                new GameSetup.Participant("c",true,2),new GameSetup.Participant("human",false,3)),"a"));
        repo.states.put("g",repo.json.writeValueAsString(game));
        int actions=0;
        while(!service.load("g").turn.playerId.equals("human")&&actions<10) {
            execute();actions++;
            assertFalse(service.botReady("g"));
            assertEquals(clock.now+2100,repo.ready.get("g"));
            clock.now=repo.ready.get("g");
        }
        assertEquals(9,actions);assertEquals("human",service.load("g").turn.playerId);
        assertNull(service.load("g").turn.movementStartedAt);
    }
    @Test void triviaAnswerWaitsBeforeCardOrContinuation() {
        var catalog=new SeedCatalog();
        engine=new TriviaRules(board,m->1,clock,30000,catalog,15000,15000);service=freshService();
        policy=new BotPolicy(engine,catalog,()->0.0);
        var pending=new GameState.PendingTrivia();pending.id="question";pending.playerId="a";
        pending.questionId="classic-1";pending.options=catalog.question("classic-1").options();
        pending.deadline=clock.now+15000;game.trivia=pending;
        repo.states.put("g",repo.json.writeValueAsString(game));
        assertEquals("TRIVIA_ANSWER",next().type());execute();
        assertNull(service.load("g").trivia);assertFalse(service.botReady("g"));
        long ready=repo.ready.get("g");assertTrue(ready>=clock.now+1500);
        assertThrows(GameException.class,this::execute);
        clock.now=ready;execute();assertEquals(2,service.load("g").version);
    }
    @Test void persistenceExceptionLeavesDetachedActionUncommitted() {
        var failing=new MemoryGames() {
            @Override public synchronized boolean commitBot(GameState g,long expected,
                    com.pixelchiss.gameservice.application.GameUpdate event,long delay,long now) {
                throw new IllegalStateException("Persistence unavailable");
            }
        };
        failing.create(game,null);
        var isolated=new GameService(failing,engine,new JsonPayloadFingerprints(repo.json),clock,pacing);
        assertThrows(IllegalStateException.class,()->isolated.botAction("a",next()));
        assertEquals(0,failing.find("g").version);assertEquals(1,failing.notifications.size());
        assertTrue(failing.ready.isEmpty());assertTrue(failing.find("g").receipts.isEmpty());
    }
    @Test void invalidConfigurationFailsFast() {assertThrows(IllegalArgumentException.class,()->new BotPacing(0,1,1,1,1,1));}
}
