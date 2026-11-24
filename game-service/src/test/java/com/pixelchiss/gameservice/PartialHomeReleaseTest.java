package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.bot.BotPolicy;
import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class PartialHomeReleaseTest extends EngineFixture {
    String own(int n) { return "seat-0-piece-" + n; }
    void setup(String map, int value, int home) {
        engine = new RuleEngine(board, maximum -> value, clock, 30_000);
        game = engine.create(new GameSetup("g", "l", map, List.of(
                new GameSetup.Participant("a", false, 0), new GameSetup.Participant("b", false, 1)), "a"));
        for (int n = home; n < 4; n++) placeAt(own(n), 12 + n * 10);
    }
    List<RuleEngine.Event> launch() {
        return engine.act(game, "a", action("ROLL_DICE", null, null, null, null, null, null));
    }
    List<RuleEngine.Event> confirm() {
        return engine.act(game, "a", new GameAction("DICE_ANIMATION_COMPLETED", "g", UUID.randomUUID().toString(),
                game.version, null, null, null, null, null, null, game.turn.rollId, null, null, null));
    }
    List<RuleEngine.Event> end() {
        return engine.act(game, "a", action("END_TURN", null, null, null, null, null, null));
    }
    @ParameterizedTest
    @CsvSource({"classic,1,1,1", "classic,2,1,1", "classic,3,1,1", "classic,4,1,1", "classic,5,1,1", "classic,6,1,1",
                "classic,5,2,2", "classic,6,3,3", "classic,1,4,4", "classic,5,0,0",
                "science,1,1,1", "science,2,1,1", "science,3,1,1", "science,4,1,1", "science,5,1,1", "science,6,1,1",
                "science,5,2,2", "science,6,3,3", "science,1,4,4", "science,5,0,0"})
    void authoritativeReleaseAndRemainingMoves(String map, int value, int home, int released) {
        setup(map, value, home);
        var events = launch();
        assertEquals(released, events.stream().filter(e -> e.type().equals("PIECE_RELEASED")).count());
        assertEquals(released, game.turn.releasedPieceIds.size());
        assertArrayEquals(home == 0 ? new boolean[]{false,false} : new boolean[]{true,home > 1}, game.turn.consumed);
        assertTrue(engine.legalMoves(game, "a").isEmpty());
        assertThrows(GameException.class, this::launch);
        var confirmed = confirm();
        if (released > 1) {
            assertFalse(game.turn.rolled); assertTrue(game.turn.extraRollAvailable);
            assertTrue(engine.legalMoves(game, "a").isEmpty());
            assertTrue(confirmed.stream().noneMatch(e -> e.type().equals("MOVEMENT_TIMER_STARTED")));
        } else {
            assertTrue(game.turn.rolled); assertFalse(game.turn.extraRollAvailable);
            assertEquals(30_000, game.turn.deadline - game.turn.movementStartedAt);
            var moves = engine.legalMoves(game, "a");
            assertFalse(moves.isEmpty());
            if (released == 1) {
                assertEquals(Set.of(own(1),own(2),own(3)),
                        new HashSet<>(moves.stream().map(RuleEngine.Move::pieceId).toList()));
                assertTrue(moves.stream().allMatch(m -> m.dieIndex() == 1 && m.dieValue() == value));
                assertEquals("PIECE_RELEASED_THIS_ROLL", assertThrows(GameException.class,
                        () -> engine.calculate(game, "a", own(0), 1)).code());
                for (int index : List.of(0, 2)) assertEquals("DIE_UNAVAILABLE", assertThrows(GameException.class,
                        () -> engine.calculate(game, "a", own(1), index)).code());
            } else assertTrue(moves.stream().anyMatch(m -> m.dieIndex() == 2 && m.dieValue() == 2 * value));
        }
    }
    @Test void remainingDieCannotBeSplitReusedOrBypassedAndRestrictionEndsOnNextRoll() {
        setup("classic", 5, 1); launch(); String oldRoll = game.turn.rollId; confirm();
        var movement = engine.calculate(game, "a", own(1), 1);
        assertEquals("INVENTED_DIE", assertThrows(GameException.class, () -> engine.act(game, "a",
                action("MOVE_REQUEST", own(1), 1, 2, movement.from(), movement.to(), null))).code());
        long deadline = game.turn.deadline;
        assertTrue(confirm().isEmpty()); assertEquals(deadline, game.turn.deadline);
        move(movement);
        assertArrayEquals(new boolean[]{true,true}, game.turn.consumed);
        assertEquals(1, game.turn.consecutiveDoubles); assertTrue(game.turn.extraRollAvailable);
        assertEquals("ROLL_REQUIRED", assertThrows(GameException.class,
                () -> engine.calculate(game,"a",own(2),1)).code());
        launch(); assertNotEquals(oldRoll, game.turn.rollId); confirm();
        assertTrue(game.turn.releasedPieceIds.isEmpty());
        assertTrue(engine.legalMoves(game,"a").stream().anyMatch(m -> m.pieceId().equals(own(0))));
        assertEquals(2, game.turn.consecutiveDoubles);
    }
    @Test void noPreviouslyActiveMoveDiscardsDieWithoutTimerOrSoplar() {
        for (int value : List.of(1,5,6)) {
            setup("classic", value, 1);
            for (int n = 1; n < 4; n++) place(own(n), Board.FINISH);
            placeAt("seat-1-piece-0", 5 + value); // only the excluded new piece could capture
            launch(); assertArrayEquals(new boolean[]{true,false}, game.turn.consumed);
            var events = confirm();
            assertArrayEquals(new boolean[]{true,true}, game.turn.consumed);
            assertTrue(game.turn.extraRollAvailable); assertFalse(game.turn.rolled);
            assertTrue(engine.legalMoves(game,"a").isEmpty()); assertNull(game.turn.movementStartedAt);
            assertTrue(game.turn.captureCandidates.isEmpty());
            assertTrue(events.stream().noneMatch(e -> Set.of("PIECE_BLOWN","MOVEMENT_TIMER_STARTED").contains(e.type())));
            assertEquals("BOARD", game.piece(own(0)).status);
        }
    }
    void captureSetup() {
        setup("classic", 5, 1); placeAt(own(1), 6);
        place(own(2), Board.FINISH); place(own(3), Board.FINISH);
        placeAt("seat-1-piece-0", 11); launch(); confirm();
    }
    @Test void remainingDieCapturesWithPreviouslyActivePiece() {
        captureSetup(); var movement = engine.calculate(game,"a",own(1),1); assertTrue(movement.capture());
        move(movement); assertEquals("HOME", game.piece("seat-1-piece-0").status);
        assertTrue(game.turn.captureSatisfied); assertTrue(game.turn.extraRollAvailable);
    }
    @Test void omissionOfRealRemainingCaptureStillBlowsOnlyEligiblePiece() {
        captureSetup(); assertEquals(Set.of(own(1)), game.turn.captureCandidates);
        var events = end();
        assertEquals("HOME", game.piece(own(1)).status); assertEquals("BOARD", game.piece(own(0)).status);
        assertEquals(1, events.stream().filter(e -> e.type().equals("PIECE_BLOWN")).count());
        assertEquals("b", game.turn.playerId);
    }
    @Test void releaseCapturesAtOwnStartOnceAndSatisfiesExistingCapturePolicy() {
        setup("classic",5,1); placeAt("seat-1-piece-0",5);
        placeAt(own(1),6); placeAt("seat-1-piece-1",11);
        var released=launch(); confirm();
        assertEquals(1,released.stream().filter(e -> e.type().equals("PIECE_CAPTURED")).count());
        assertEquals("HOME",game.piece("seat-1-piece-0").status); assertTrue(game.turn.captureSatisfied);
        assertTrue(end().stream().noneMatch(e -> e.type().equals("PIECE_BLOWN")));
    }
    @Test void animationAndRemainingMoveExpiryKeepNormalPolicyAndClearOldTimers() {
        setup("classic",5,1); launch();
        assertEquals(10_000,game.turn.animationDeadline-game.turn.rollPublishedAt);
        assertEquals("ANIMATION_PENDING",assertThrows(GameException.class,() -> engine.act(game,"a",
                action("MOVE_REQUEST",own(1),1,5,22,27,null))).code());
        clock.now+=3_000; confirm(); long deadline=game.turn.deadline;
        assertEquals(clock.now+30_000,deadline); assertThrows(GameException.class,this::launch);
        clock.now=deadline; var expired=engine.tick(game);
        assertEquals(List.of(1),expired.stream().filter(e -> e.type().equals("TURN_EXPIRED")).findFirst().orElseThrow().data().get("discardedDice"));
        assertEquals("b",game.turn.playerId); assertEquals(0,game.turn.consecutiveDoubles);
        assertNull(game.turn.movementStartedAt); assertNull(game.turn.rollId); assertFalse(game.turn.extraRollAvailable);
        assertTrue(engine.tick(game).isEmpty());
    }
    @Test void botUsesSameRemainingDieThenTwoMorePairsCrownAndClose() {
        setup("science",5,1); game.player("a").bot=true;
        var bot=new BotPolicy(engine,new SeedCatalog(),()->.5);
        engine.act(game,"a",bot.next(game));
        var remaining=bot.next(game); assertEquals("MOVE_REQUEST",remaining.type());
        assertNotEquals(own(0),remaining.pieceId()); assertEquals(1,remaining.dieIndex());
        engine.act(game,"a",remaining); assertEquals("ROLL_DICE",bot.next(game).type());
        engine.act(game,"a",bot.next(game));
        while (game.turn.rolled && !game.turn.crownChoiceRequired) engine.act(game,"a",bot.next(game));
        engine.act(game,"a",bot.next(game)); assertEquals("CROWN_REQUEST",bot.next(game).type());
        engine.act(game,"a",bot.next(game)); assertEquals("b",game.turn.playerId);
        assertNull(bot.next(game)); assertEquals(0,game.turn.consecutiveDoubles);
    }
    @Test void thirdPairReleaseGoesDirectlyToChosenCrownAndNeverToRemainingMovement() {
        setup("classic",5,1); game.turn.consecutiveDoubles=2; place(own(1),Board.FIRST_FINISH_PROGRESS);
        place(own(2),Board.FINISH);
        var rolled=launch(); assertArrayEquals(new boolean[]{true,true},game.turn.consumed);
        var confirmed=confirm();
        assertTrue(game.turn.thirdDoubleReward); assertTrue(game.turn.crownChoiceRequired);
        assertTrue(engine.legalMoves(game,"a").isEmpty()); assertNull(game.turn.movementStartedAt);
        assertTrue(confirmed.stream().noneMatch(e -> e.type().equals("MOVEMENT_TIMER_STARTED")));
        assertTrue(rolled.stream().anyMatch(e -> e.type().equals("CROWN_CHOICE_REQUIRED")));
        for (String id : List.of(own(0),own(2),"seat-1-piece-0")) assertEquals("INVALID_CROWN_PIECE",
                assertThrows(GameException.class,() -> engine.act(game,"a",action("CROWN_REQUEST",id,null,null,null,null,null))).code());
        var crown=action("CROWN_REQUEST",own(1),null,null,null,null,null);
        var events=engine.act(game,"a",crown);
        assertEquals(1,events.stream().filter(e -> e.type().equals("PIECE_CROWNED")).count());
        assertEquals("b",game.turn.playerId); assertNull(game.turn.rollId); assertNull(game.turn.movementStartedAt);
        assertFalse(game.turn.crownChoiceRequired); assertFalse(game.turn.thirdDoubleReward);
        assertEquals("NOT_YOUR_TURN",assertThrows(GameException.class,this::launch).code());
        assertEquals("NOT_YOUR_TURN",assertThrows(GameException.class,() -> engine.act(game,"a",crown)).code());
    }
    @Test void triviaOnRemainingDieGetsFifteenSecondsAndExtraRollWaitsForResolution() {
        var catalog=new SeedCatalog() {
            @Override public List<CardDefinition> cards(String map,String type) { return List.of(); }
        };
        engine=new TriviaRules(board,m->1,clock,30_000,catalog,15_000,15_000);
        create();placeAt(own(1),7);place(own(2),Board.FINISH);place(own(3),Board.FINISH);
        launch();confirm();clock.now+=12_000;
        move(engine.calculate(game,"a",own(1),1));
        assertNotNull(game.trivia);assertEquals(clock.now+15_000,game.trivia.deadline);
        assertEquals(18_000,game.turn.movementRemainingMillis);
        assertArrayEquals(new boolean[]{true,true},game.turn.consumed);assertFalse(game.turn.extraRollAvailable);
        assertEquals("PENDING_CHALLENGE",assertThrows(GameException.class,this::launch).code());
        clock.now=game.trivia.deadline;var events=engine.tick(game);
        assertNull(game.trivia);assertNull(game.turn.movementRemainingMillis);assertNull(game.turn.movementStartedAt);
        assertEquals(1,events.stream().filter(e->e.type().equals("TRIVIA_RESOLVED")).count());
        assertTrue(events.stream().noneMatch(e->e.type().equals("MOVEMENT_TIMER_RESUMED")));
        assertTrue(game.turn.extraRollAvailable);assertEquals(1,game.turn.consecutiveDoubles);
    }
    @Test void botWithNoPreviouslyActivePieceDiscardsRemainingDieAndRollsAgain() {
        setup("science",6,1);game.player("a").bot=true;
        for(int n=1;n<4;n++) place(own(n),Board.FINISH);
        var bot=new BotPolicy(engine,new SeedCatalog(),()->.5);
        engine.act(game,"a",bot.next(game));
        assertTrue(game.turn.extraRollAvailable);assertFalse(game.turn.rolled);
        assertArrayEquals(new boolean[]{true,true},game.turn.consumed);
        assertEquals("ROLL_DICE",bot.next(game).type());assertTrue(game.turn.captureCandidates.isEmpty());
    }
    @Test void activePiecesWithOnlyOvershootsDoNotCreateAnUnplayableMovementWindow() {
        setup("science",5,1);
        for(int n=1;n<4;n++) place(own(n),Board.FINISH-4+n);
        launch();assertEquals(3,game.turn.crownEligible.size());var events=confirm();
        assertTrue(engine.legalMoves(game,"a").isEmpty());assertTrue(game.turn.extraRollAvailable);
        assertNull(game.turn.movementStartedAt);assertArrayEquals(new boolean[]{true,true},game.turn.consumed);
        assertTrue(events.stream().noneMatch(e->Set.of("MOVEMENT_TIMER_STARTED","PIECE_BLOWN").contains(e.type())));
    }
    @Test void thirdPairWithOnlyNewlyReleasedPieceClosesWithoutAutomaticCrown() {
        setup("classic",5,1);game.turn.consecutiveDoubles=2;
        for(int n=1;n<4;n++) place(own(n),Board.FINISH);
        launch();assertTrue(game.turn.crownEligible.isEmpty());assertFalse(game.turn.crownChoiceRequired);
        assertArrayEquals(new boolean[]{true,true},game.turn.consumed);
        var events=confirm();assertEquals("b",game.turn.playerId);assertEquals(0,game.turn.consecutiveDoubles);
        assertEquals("BOARD",game.piece(own(0)).status);
        assertTrue(events.stream().noneMatch(e->Set.of("PIECE_CROWNED","ROLL_AVAILABLE","MOVEMENT_TIMER_STARTED").contains(e.type())));
    }
    @Test void unconfirmedPartialReleaseExpiresWithoutInventingCaptureObligations() {
        setup("classic",5,1);placeAt(own(1),6);placeAt("seat-1-piece-0",11);
        launch();clock.now=game.turn.animationDeadline;var events=engine.tick(game);
        assertTrue(events.stream().anyMatch(e->e.type().equals("TURN_EXPIRED")&&e.data().get("reason").equals("ANIMATION_TIMEOUT")));
        assertTrue(events.stream().noneMatch(e->Set.of("PIECE_BLOWN","MOVEMENT_TIMER_STARTED").contains(e.type())));
        assertEquals("b",game.turn.playerId);assertEquals("BOARD",game.piece(own(1)).status);
    }
}
