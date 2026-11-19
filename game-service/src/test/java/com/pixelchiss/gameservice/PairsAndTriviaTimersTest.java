package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.bot.BotPolicy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PairsAndTriviaTimersTest extends EngineFixture {
    String own(int n) { return "seat-0-piece-"+n; }
    void source(int... values) {
        int[] index={0};
        engine=new RuleEngine(board,m->values[index[0]++ % values.length],clock,30_000);
        create();
    }
    List<RuleEngine.Event> rawRoll() { return engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null)); }
    GameAction ack(String roll) { return new GameAction("DICE_ANIMATION_COMPLETED","g",UUID.randomUUID().toString(),game.version,
            null,null,null,null,null,null,roll,null,null,null); }
    List<RuleEngine.Event> confirm() { return engine.act(game,"a",ack(game.turn.rollId)); }
    @Test void animationAndMovementUseOneClockSampleForExactDurations() {
        var ticking=new MutableClock(){@Override public long millis(){return now++;}};
        engine=new RuleEngine(board,m->2,ticking,30_000);create();activateHomeForMovement();
        rawRoll();assertEquals(10_000,game.turn.animationDeadline-game.turn.rollPublishedAt);
        confirm();assertEquals(30_000,game.turn.deadline-game.turn.movementStartedAt);
        create();activateHomeForMovement();game.player("a").bot=true;
        rawRoll();assertEquals(30_000,game.turn.deadline-game.turn.movementStartedAt);
    }
    @Test void everyPairConsumesBothDiceForAnyHomeCountWithoutMovementTimerOrFictitiousCapture() {
        for(int value=1;value<=6;value++) for(int home=1;home<=4;home++) {
            source(value,value);
            for(int n=home;n<4;n++) place(own(n),10+n);
            placeAt("seat-1-piece-0",15+value);
            var events=rawRoll();
            int expected=Math.min(home,value==1?4:value==6?3:2);
            assertEquals(expected,events.stream().filter(e->e.type().equals("PIECE_RELEASED")).count());
            assertTrue(game.turn.homeRelease);
            assertArrayEquals(new boolean[]{true,true},game.turn.consumed);
            assertTrue(engine.legalMoves(game,"a").isEmpty());
            assertThrows(GameException.class,()->rawRoll());
            var confirmed=confirm();
            assertTrue(confirmed.stream().noneMatch(e->e.type().equals("MOVEMENT_TIMER_STARTED")));
            assertNull(game.turn.movementStartedAt);
            assertTrue(game.turn.extraRollAvailable);assertFalse(game.turn.rolled);
            assertTrue(game.turn.captureCandidates.isEmpty());
            assertTrue(engine.act(game,"a",ack(game.turn.rollId)).isEmpty());
            assertEquals(expected,events.stream().filter(e->e.type().equals("PIECE_RELEASED")).count());
            assertThrows(GameException.class,()->move(engine.calculate(game,"a",own(0),0)));
        }
    }
    @Test void releaseDiceCannotCreateCaptureObligationOrSoplarForAnActivePiece() {
        source(2,2);placeAt(own(0),6);placeAt("seat-1-piece-0",8);
        rawRoll();confirm();
        assertTrue(game.turn.captureCandidates.isEmpty());
        clock.now=game.turn.deadline;
        var events=engine.tick(game);
        assertTrue(events.stream().noneMatch(e->e.type().equals("PIECE_BLOWN")));
        assertEquals("BOARD",game.piece(own(0)).status);
        assertEquals(6,game.piece(own(0)).position);
        assertEquals("BOARD",game.piece("seat-1-piece-0").status);
    }
    @Test void noHomePairMovesNormallyThenNextRollHasOwnIdAndNonPairResetsCounter() {
        source(2,2,3,4);activateHomeForMovement();
        rawRoll();String id=game.turn.rollId;confirm();
        assertFalse(game.turn.homeRelease);assertNotNull(game.turn.movementStartedAt);
        assertThrows(GameException.class,()->rawRoll());
        move(engine.calculate(game,"a",own(0),2));
        assertTrue(game.turn.extraRollAvailable);assertEquals(1,game.turn.consecutiveDoubles);
        rawRoll();assertNotEquals(id,game.turn.rollId);assertEquals(0,game.turn.consecutiveDoubles);
        assertEquals("STALE_ROLL",assertThrows(GameException.class,()->engine.act(game,"a",ack(id))).code());
        assertTrue(engine.legalMoves(game,"a").isEmpty());confirm();
        assertFalse(engine.legalMoves(game,"a").isEmpty());
    }
    @Test void threePairsReleaseFirstThenCrownOnlyPreviouslyActiveOwnPieceAndEndWithoutFourthRoll() {
        source(2,2);rawRoll();confirm();assertEquals(1,game.turn.consecutiveDoubles);
        rawRoll();confirm();assertEquals(2,game.turn.consecutiveDoubles);
        // Home created between rolls still has mandatory release priority on the third pair.
        game.piece(own(3)).home();place(own(0),69);place(own(2),Board.FINISH);
        rawRoll();confirm();assertTrue(game.turn.homeRelease);assertTrue(game.turn.crownChoiceRequired);
        assertEquals(3,game.turn.consecutiveDoubles);
        assertFalse(game.turn.crownEligible.contains(own(3)));
        for(String id:List.of(own(3),own(2),"seat-1-piece-0"))
            assertThrows(GameException.class,()->engine.act(game,"a",action("CROWN_REQUEST",id,null,null,null,null,null)));
        var events=engine.act(game,"a",action("CROWN_REQUEST",own(0),null,null,null,null,null));
        assertEquals(1,events.stream().filter(e->e.type().equals("PIECE_CROWNED")).count());
        assertTrue(events.stream().noneMatch(e->e.type().equals("PIECE_BLOWN")));
        assertEquals("CROWNED",game.piece(own(0)).status);assertEquals("b",game.turn.playerId);
        assertEquals(0,game.turn.consecutiveDoubles);assertFalse(game.turn.extraRollAvailable);
        assertThrows(GameException.class,()->rawRoll());
    }
    @Test void thirdPairWithNoEligiblePieceClosesWithoutCrowningHomeOrNewlyReleasedPieces() {
        source(1,1);game.turn.consecutiveDoubles=2;rawRoll();
        assertFalse(game.turn.crownChoiceRequired);assertTrue(game.turn.crownEligible.isEmpty());
        confirm();assertEquals("b",game.turn.playerId);
        assertTrue(game.pieces.stream().noneMatch(p->p.status.equals("CROWNED")));
    }
    @Test void unresolvedMovementPairTimesOutWithoutExtraRoll() {
        source(4,4);activateHomeForMovement();rawRoll();confirm();
        clock.now=game.turn.deadline;engine.tick(game);
        assertEquals("b",game.turn.playerId);assertFalse(game.turn.extraRollAvailable);
    }
    @Test void botsReleaseAndRepeatWithoutAnimationThenSelectThirdPairCrown() {
        source(2,2);game.player("a").bot=true;
        var bot=new BotPolicy(engine,new SeedCatalog(),()->.5);
        for(int n=0;n<2;n++) {engine.act(game,"a",bot.next(game));assertEquals("ROLL_DICE",bot.next(game).type());}
        assertEquals(4,game.pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("BOARD")).count());
        engine.act(game,"a",bot.next(game));assertEquals("CROWN_REQUEST",bot.next(game).type());
        engine.act(game,"a",bot.next(game));assertEquals("b",game.turn.playerId);
    }
    void trivia() {
        engine=new TriviaRules(board,m->1,clock,30_000,new SeedCatalog(),15_000,15_000);
        create();activateHomeForMovement();placeAt(own(0),7);
        game.turn.rolled=true;game.turn.dice=new int[]{1,2};game.turn.animationStatus="COMPLETED";
        game.turn.rollId="trivia-roll";game.turn.movementStartedAt=clock.now;
        game.turn.deadline=clock.now+30_000;
        clock.now+=12_000;
        move(engine.calculate(game,"a",own(0),0));
    }
    GameAction answer() { return new GameAction("TRIVIA_ANSWER","g",UUID.randomUUID().toString(),game.version,
            null,null,null,null,null,null,game.trivia.id,0,null,null); }
    @Test void triviaGetsFullFifteenSecondsAndResumesExactlyRemainingMovement() {
        trivia();assertEquals(18_000,game.turn.movementRemainingMillis);
        assertEquals(clock.now+15_000,game.trivia.deadline);
        long triviaDeadline=game.trivia.deadline;clock.now+=10_000;
        var events=engine.act(game,"a",answer());
        assertTrue(events.stream().anyMatch(e->e.type().equals("MOVEMENT_TIMER_RESUMED")));
        assertEquals(clock.now+18_000,game.turn.deadline);assertNull(game.turn.movementRemainingMillis);
        assertTrue(triviaDeadline<game.turn.deadline);
    }
    @Test void triviaOutlivesOldMovementDeadlineAndTimeoutResolvesOnceRejectingLateAnswer() {
        trivia();
        // A question can start with less than 15s of movement left.
        game.turn.movementRemainingMillis=2_000L;
        game.turn.deadline=clock.now+2_000;
        GameAction late=answer();clock.now+=3_000;
        assertTrue(engine.tick(game).isEmpty());assertNotNull(game.trivia);
        clock.now=game.trivia.deadline;
        assertEquals("CHALLENGE_EXPIRED",assertThrows(GameException.class,()->engine.act(game,"a",late)).code());
        var events=engine.tick(game);assertNull(game.trivia);
        assertEquals(1,events.stream().filter(e->e.type().equals("TRIVIA_RESOLVED")).count());
        assertEquals(clock.now+2_000,game.turn.deadline);assertTrue(engine.tick(game).isEmpty());
        assertThrows(GameException.class,()->engine.act(game,"a",late));
        clock.now=game.turn.deadline;engine.tick(game);assertEquals("b",game.turn.playerId);
    }
    @Test void triviaOnLastDieDoesNotResumeAnObsoleteMovementWindow() {
        trivia();game.turn.consumed[1]=true;
        var events=engine.act(game,"a",answer());
        assertTrue(events.stream().noneMatch(e->e.type().equals("MOVEMENT_TIMER_RESUMED")));
        assertNull(game.turn.movementStartedAt);assertNull(game.turn.movementRemainingMillis);
        assertEquals("b",game.turn.playerId);
    }
    @Test void delayedRecoveryDoesNotExtendAlreadyExpiredMovementAfterTriviaTimeout() {
        trivia();long expected=game.trivia.deadline+game.turn.movementRemainingMillis;
        clock.now=expected+1;
        var events=engine.tick(game);
        assertEquals(1,events.stream().filter(e->e.type().equals("TRIVIA_RESOLVED")).count());
        assertTrue(events.stream().anyMatch(e->e.type().equals("TURN_EXPIRED")));
        assertEquals("b",game.turn.playerId);
        assertNull(game.turn.movementRemainingMillis);
    }
    @Test void retiringPausedOwnerNeverResumesTheirMovementTimer() {
        trivia();engine.retire(game,"a");
        assertEquals("b",game.turn.playerId);assertNull(game.turn.movementRemainingMillis);
        assertNull(game.trivia);assertNull(game.turn.movementStartedAt);
    }
}
