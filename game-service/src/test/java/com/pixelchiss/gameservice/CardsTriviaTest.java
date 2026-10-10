package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.domain.GameState.*;
import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.engine.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CardsTriviaTest extends EngineFixture {
    final SeedCatalog catalog=new SeedCatalog();
    TriviaRules cards;
    @BeforeEach void setupCards(){cards=new TriviaRules(board,m->Math.min(4,m),clock,60_000,catalog,15_000,15_000);engine=cards;}
    void grant(String id){cards.grant(game,catalog.card(id),"a",new ArrayList<>());}
    Action target(CardChoice choice){return new Action("CARD_TARGET","g","target",game.version,choice.pieceId(),null,null,null,choice.targetPosition(),null,game.card.id,null,choice.targetPieceId(),choice.targetPlayerId());}
    @Test void captureCardOnlyAcceptsNearestReachableUnprotectedOpponent() {
        place("seat-0-piece-0",3);place("seat-1-piece-0",53); // physical 4 nearest
        place("seat-1-piece-1",55); // physical 6 farther
        grant("capture-next");assertEquals(1,game.card.choices.size());
        var valid=game.card.choices.getFirst();assertEquals("seat-1-piece-0",valid.targetPieceId());
        assertThrows(GameException.class,()->cards.act(game,"a",target(new CardChoice(valid.pieceId(),"seat-1-piece-1",null,6))));
        var request=target(valid);cards.act(game,"a",request);assertEquals("HOME",game.piece(valid.targetPieceId()).status);
        assertEquals(2,game.piece(valid.pieceId()).position);assertNull(game.card);
        assertThrows(GameException.class,()->cards.act(game,"a",request));
    }
    @Test void moveSafeFindsFirstAvailableSafeAndRejectsArbitraryPosition() {
        place("seat-0-piece-0",4);place("seat-1-piece-0",54);place("seat-1-piece-1",54); // safe5 full
        grant("move-safe");var choice=game.card.choices.getFirst();assertEquals(10,choice.targetPosition());
        assertThrows(GameException.class,()->cards.act(game,"a",target(new CardChoice(choice.pieceId(),null,null,5))));
        cards.act(game,"a",target(choice));assertEquals(10,game.piece(choice.pieceId()).position);
    }
    @Test void impossibleCardIsDiscardedAndNotStored() {
        var events=new ArrayList<RuleEngine.Event>();cards.grant(game,catalog.card("capture-next"),"a",events);
        assertNull(game.card);assertEquals("CARD_DISCARDED",events.getFirst().type());
    }
    void expireTurn(){clock.now=game.turn.deadline;cards.tick(game);}
    void nextOwnTurn(){while(!game.turn.playerId.equals("a"))expireTurn();}
    @Test void diceLimitLastsExactlyThreeAffectedOwnTurnsIncludingTimeouts() {
        grant("dice-limit");assertEquals(3,game.player("a").effects.getFirst().remainingTurns);
        expireTurn();nextOwnTurn();
        for(int remaining=3;remaining>=1;remaining--) {
            assertEquals(remaining,game.player("a").effects.getFirst().remainingTurns);
            roll();assertArrayEquals(new int[]{3,3},game.turn.dice);
            expireTurn();nextOwnTurn();
        }
        assertTrue(game.player("a").effects.isEmpty());roll();assertArrayEquals(new int[]{4,4},game.turn.dice);
    }
    @Test void chosenDiceOnlyOnNextOwnTurnAndLimitHasPrecedence() {
        grant("choose-dice");grant("dice-limit");
        assertThrows(GameException.class,()->cards.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(2,3))));
        expireTurn();nextOwnTurn();
        assertThrows(GameException.class,()->cards.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(6,6))));
        assertTrue(game.player("a").effects.stream().anyMatch(e->e.code.equals("CHOOSE_DICE")));
        cards.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(3,3)));
        assertTrue(game.player("a").effects.stream().noneMatch(e->e.code.equals("CHOOSE_DICE")));
        expireTurn();nextOwnTurn();
        assertThrows(GameException.class,()->cards.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(3,3))));
    }
    @Test void chooseDiceAllowsFullRangeWithoutLimitAndConsumesOnce() {
        grant("choose-dice");expireTurn();nextOwnTurn();
        cards.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(1,6)));
        // No movable home piece and no doubles: turn advances automatically.
        assertTrue(game.player("a").effects.isEmpty());nextOwnTurn();
        assertThrows(GameException.class,()->cards.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(1,6))));
    }
    @Test void triviaHidesAnswerUntilResolvedAndPausesMoves() {
        place("seat-0-piece-0",5);roll();move(cards.calculate(game,"a","seat-0-piece-0",0)); // cell8
        assertNotNull(game.trivia);
        String publicState=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(GameView.of(game));
        assertFalse(publicState.contains("correctIndex"));
        assertThrows(GameException.class,()->cards.act(game,"a",action("END_TURN",null,null,null,null,null,null)));
        var q=catalog.question(game.trivia.questionId);
        var answer=new Action("TRIVIA_ANSWER","g","answer",0L,null,null,null,null,null,null,game.trivia.id,q.correctIndex(),null,null);
        var events=cards.act(game,"a",answer);assertEquals("TRIVIA_RESOLVED",events.getFirst().type());assertNull(game.trivia);
        assertThrows(GameException.class,()->cards.act(game,"a",answer));
    }
    @Test void triviaAndCardTimeoutAreControlledByServer() {
        place("seat-0-piece-0",5);roll();move(cards.calculate(game,"a","seat-0-piece-0",0));
        clock.now=game.trivia.deadline;cards.tick(game);assertNull(game.trivia);
        assertEquals("DICE_MAX_LIMIT",game.player("a").effects.getFirst().code);
        place("seat-0-piece-1",4);grant("move-safe");clock.now=game.card.deadline;cards.tick(game);assertNull(game.card);
    }
    @Test void catalogFailureCannotKeepAnExpiredTurnBlocked() {
        place("seat-0-piece-0",5);roll();move(cards.calculate(game,"a","seat-0-piece-0",0));
        var unavailable=new SeedCatalog(){@Override public QuestionDefinition question(String id){throw new GameException("CATALOG_UNAVAILABLE","Unavailable");}};
        var timer=new TriviaRules(board,m->4,clock,60000,unavailable,15000,15000);
        clock.now=game.turn.deadline;var events=timer.tick(game);
        assertEquals("b",game.turn.playerId);assertNull(game.trivia);assertNull(game.card);
        assertTrue(events.stream().anyMatch(e->e.type().equals("CARD_DISCARDED")));
    }
    @Test void reapplyingDiceCardsReplacesEffectsWithoutAccumulatingAnInventory() {
        grant("dice-limit");grant("dice-limit");grant("choose-dice");grant("choose-dice");
        assertEquals(2,game.player("a").effects.size());
        assertEquals(3,game.player("a").effects.stream().filter(e->e.code.equals("DICE_MAX_LIMIT")).findFirst().orElseThrow().remainingTurns);
        assertEquals(1,game.player("a").effects.stream().filter(e->e.code.equals("CHOOSE_DICE")).findFirst().orElseThrow().remainingTurns);
        assertNull(game.card);
    }
    @Test void diceLimitCountsExtraOwnTurnsButNotRivalTurns() {
        game.player("a").effects.add(new Effect("DICE_MAX_LIMIT",3,game.player("a").turnsStarted,3));
        roll();move(cards.calculate(game,"a","seat-0-piece-0",0));move(cards.calculate(game,"a","seat-0-piece-1",1));
        assertEquals("a",game.turn.playerId);assertEquals(2,game.player("a").effects.getFirst().remainingTurns);
        expireTurn();assertEquals(1,game.player("a").effects.getFirst().remainingTurns);
        nextOwnTurn();assertEquals(1,game.player("a").effects.getFirst().remainingTurns);
        roll();move(cards.calculate(game,"a","seat-0-piece-2",0));move(cards.calculate(game,"a","seat-0-piece-3",1));
        assertEquals("a",game.turn.playerId);assertTrue(game.player("a").effects.isEmpty());
        roll();assertArrayEquals(new int[]{4,4},game.turn.dice);
    }
}
