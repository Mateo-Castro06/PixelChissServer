package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.GameAction;
import com.pixelchiss.gameservice.domain.GameState.CardChoice;
import com.pixelchiss.gameservice.engine.GameException;
import com.pixelchiss.gameservice.engine.RuleEngine.Event;
import com.pixelchiss.gameservice.engine.TriviaRules;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseCardsTest extends EngineFixture {
    private final SeedCatalog catalog = new SeedCatalog();
    private final ArrayDeque<Integer> rolls = new ArrayDeque<>();
    private TriviaRules rules;

    @BeforeEach void cards() {
        rules = new TriviaRules(board, maximum -> rolls.removeFirst(), clock, 60_000, catalog, 15_000, 15_000);
        engine = rules;
    }

    private List<Event> grant(String id) {
        var events = new ArrayList<Event>();
        rules.grant(game, catalog.card(id), "a", events);
        return events;
    }

    private List<Event> endByTimeout() {
        clock.now = game.turn.deadline;
        return rules.tick(game);
    }

    private GameAction select(CardChoice choice) {
        return new GameAction("CARD_TARGET", game.id, "select", game.version, choice.pieceId(),
                null, null, null, choice.targetPosition(), null, game.card.id, null,
                choice.targetPieceId(), choice.targetPlayerId());
    }

    private List<Event> rollAs(String actor, Integer... draws) {
        rolls.addAll(List.of(draws));
        var events = rules.act(game, actor, action("ROLL_DICE",null,null,null,null,null,null));
        assertTrue(rolls.isEmpty(), "Unexpected extra draw remained unused");
        return events;
    }

    @Test void closestCaptureTakesAllRivalsOnSafeCellAndBreaksTieByOwnPieceNumber() {
        place("seat-0-piece-0",5);  // physical 4
        place("seat-0-piece-1",10); // physical 9
        place("seat-1-piece-0",54); // physical 5
        place("seat-2-piece-0",38); // physical 5
        place("seat-1-piece-1",59); // physical 10, equally near the other origin
        place("seat-1-piece-2",65); // private lane
        var events = grant("kill-next");
        assertEquals(2, events.stream().filter(e -> e.type().equals("PIECE_CAPTURED")).count());
        assertEquals("HOME",game.piece("seat-1-piece-0").status);
        assertEquals("HOME",game.piece("seat-2-piece-0").status);
        assertEquals("BOARD",game.piece("seat-1-piece-1").status);
        assertEquals("BOARD",game.piece("seat-1-piece-2").status);
        assertEquals(4,game.piece("seat-0-piece-0").position);
    }

    @Test void chosenCaptureUsesExistingTargetPositionAndCannotTargetOwnOrPrivateCells() {
        place("seat-0-piece-0",6);
        place("seat-1-piece-0",54); // shares safe 5 with own piece
        place("seat-2-piece-0",39);
        place("seat-1-piece-1",65);
        grant("kill-chosen");
        assertEquals(List.of(new CardChoice(null,null,null,5),new CardChoice(null,null,null,6)), game.card.choices);
        assertEquals("INVALID_CARD_TARGET", assertThrows(GameException.class, () -> rules.act(game,"a",
                select(new CardChoice(null,null,null,84)))).code());
        var action = select(game.card.choices.getFirst());
        var events = rules.act(game,"a",action);
        assertEquals(1, events.stream().filter(e -> e.type().equals("CARD_PIECE_RETURNED")).count());
        assertEquals("HOME",game.piece("seat-1-piece-0").status);
        assertEquals("BOARD",game.piece("seat-0-piece-0").status);
        assertNull(game.card);
        assertEquals("INVALID_CHALLENGE",assertThrows(GameException.class,() -> rules.act(game,"a",action)).code());
    }

    @Test void advanceUsesNextSafeWithCapacityAndCannotLeavePrivateLane() {
        place("seat-0-piece-0",5);
        place("seat-0-piece-1",65);
        place("seat-1-piece-0",54);
        place("seat-2-piece-0",38); // safe 5 full
        grant("advance-safe");
        var choice = game.card.choices.getFirst();
        assertEquals(List.of(new CardChoice("seat-0-piece-0",null,null,10)),game.card.choices);
        rules.act(game,"a",select(choice));
        assertEquals(10,game.piece("seat-0-piece-0").position);
        assertEquals(80,game.piece("seat-0-piece-1").position);
    }

    @Test void missingTargetsDiscardWithoutChangingGame() {
        for (String id : List.of("kill-next","kill-chosen","advance-safe","instant-death")) {
            assertEquals("NO_LEGAL_TARGET",grant(id).getFirst().data().get("reason"));
            assertNull(game.card);
            assertTrue(game.pieces.stream().allMatch(p -> p.status.equals("HOME")));
        }
    }

    @Test void instantDeathReturnsLeadingUncrownedPieceAndBreaksTieByNumber() {
        place("seat-0-piece-0",65);
        place("seat-0-piece-1",65);
        place("seat-0-piece-2",68);
        grant("instant-death");
        assertEquals("HOME",game.piece("seat-0-piece-0").status);
        assertEquals("BOARD",game.piece("seat-0-piece-1").status);
        assertEquals("CROWNED",game.piece("seat-0-piece-2").status);
    }

    @Test void skipIsConsumedOnceAndKeepsClockwiseOrder() {
        grant("skip-turn");
        grant("skip-turn"); // replace, do not stack
        endByTimeout();
        assertEquals("b",game.turn.playerId);
        endByTimeout();
        assertEquals("c",game.turn.playerId);
        var events = endByTimeout();
        assertEquals("b",game.turn.playerId);
        assertEquals(5,game.turn.number);
        assertTrue(events.stream().anyMatch(e -> e.type().equals("EFFECT_CONSUMED")
                && "SKIP_NEXT_TURN".equals(e.data().get("effectCode"))));
        assertTrue(game.player("a").effects.isEmpty());
        endByTimeout(); endByTimeout();
        assertEquals("a",game.turn.playerId);
    }

    @Test void doublesReturnsOnePerRollUntilEndOfNextOwnTurnIncludingOwnDouble() {
        place("seat-0-piece-0",5);
        place("seat-0-piece-1",8);
        place("seat-0-piece-2",68);
        grant("doubles-death");
        endByTimeout();
        rollAs("b",3,3,2); // equal odd dice also qualify; pick second board piece
        assertEquals("HOME",game.piece("seat-0-piece-1").status);
        assertEquals("BOARD",game.piece("seat-0-piece-0").status);
        endByTimeout();
        rollAs("c",2,4); // both even but not a pair; auto advances (all home)
        assertEquals("BOARD",game.piece("seat-0-piece-0").status);
        assertEquals("a",game.turn.playerId);
        rollAs("a",5,5,1);
        assertEquals("HOME",game.piece("seat-0-piece-0").status);
        assertEquals("CROWNED",game.piece("seat-0-piece-2").status);
        endByTimeout();
        assertTrue(game.player("a").effects.isEmpty());
        place("seat-0-piece-0",7);
        assertTrue(rollAs("b",4,4).stream().noneMatch(e -> e.type().equals("CARD_PIECE_RETURNED")));
        assertEquals("BOARD",game.piece("seat-0-piece-0").status);
    }

    @Test void doublesDoesNotDrawWhenVictimHasNoEligiblePieces() {
        grant("doubles-death");
        endByTimeout();
        assertTrue(rollAs("b",2,2).stream().noneMatch(e -> e.type().equals("CARD_PIECE_RETURNED")));
    }
}
