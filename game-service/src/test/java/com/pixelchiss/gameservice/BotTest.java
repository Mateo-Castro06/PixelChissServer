package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.bot.*;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.dto.Action;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotTest extends EngineFixture {
    BotPolicy bot;
    SeedCatalog catalog=new SeedCatalog();
    @BeforeEach void setupBot(){game.player("a").bot=true;bot=new BotPolicy(engine,catalog,()->0.0,0.65);}
    @Test void botCombinesCaptureAndSafetyUsingBothAuthorizedDice() {
        place("seat-0-piece-0",3);place("seat-0-piece-1",2);place("seat-1-piece-0",55);
        place("seat-2-piece-0",43);place("seat-2-piece-1",43); // safe10 full
        roll();var first=bot.next(game);assertEquals("seat-0-piece-0",first.pieceId());
        engine.act(game,"a",first);assertEquals("HOME",game.piece("seat-1-piece-0").status);
        var second=bot.next(game);assertEquals("seat-0-piece-1",second.pieceId());assertEquals(5,second.targetPosition());
        assertNotEquals(first.dieIndex(),second.dieIndex());engine.act(game,"a",second);
        assertEquals(5,game.piece("seat-0-piece-1").position);
    }
    @Test void botPrefersCrownThenSafeAndCanReleaseDifferentPieces() {
        place("seat-0-piece-0",64);place("seat-0-piece-1",2);roll();
        assertEquals("seat-0-piece-0",bot.next(game).pieceId());engine.act(game,"a",bot.next(game));
        assertEquals(5,bot.next(game).targetPosition());
        create();game.player("a").bot=true;roll();var first=bot.next(game);engine.act(game,"a",first);
        var second=bot.next(game);assertNotEquals(first.pieceId(),second.pieceId());engine.act(game,"a",second);
        assertEquals(2,game.pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("BOARD")).count());
    }
    @Test void noLegalMovesEndTurnAndBotsCannotBypassValidation() {
        game.turn.rolled=true;game.turn.dice=new int[]{1,2};
        var action=bot.next(game);assertEquals("END_TURN",action.type());engine.act(game,"a",action);assertEquals("b",game.turn.playerId);
        assertThrows(GameException.class,()->engine.act(game,"a",action));
    }
    @Test void botsResolveCardAndTriviaAndChooseOnlyAuthorizedDice() {
        var rules=new TriviaRules(board,m->Math.min(4,m),clock,60_000,catalog,15_000,15_000);
        bot=new BotPolicy(rules,catalog,()->0.0,1.0);
        place("seat-0-piece-0",4);rules.grant(game,catalog.card("move-safe"),"a",new ArrayList<>());
        rules.act(game,"a",bot.next(game));assertTrue(board.protectedCell(game.piece("seat-0-piece-0").position));
        var pending=new GameState.PendingTrivia();pending.id="q";pending.playerId="a";pending.questionId="classic-1";
        pending.options=catalog.question("classic-1").options();pending.deadline=clock.millis()+15000;
        game.turn.playerId="a";game.trivia=pending;
        Action answer=bot.next(game);assertEquals(1,answer.answerIndex());rules.act(game,"a",answer);assertNull(game.trivia);
        game.card=null;game.turn.rolled=false;game.turn.playerId="a";
        game.player("a").effects.add(new GameState.Effect("CHOOSE_DICE",1,game.player("a").turnsStarted,6));
        game.player("a").effects.add(new GameState.Effect("DICE_MAX_LIMIT",3,game.player("a").turnsStarted,3));
        assertEquals(List.of(3,3),bot.next(game).chosenDice());rules.act(game,"a",bot.next(game));
        assertArrayEquals(new int[]{3,3},game.turn.dice);
    }
    @Test void weightedSelectionCanChooseAnotherReasonablePiece() {
        roll();var first=bot.next(game);
        var alternative=new BotPolicy(engine,catalog,()->0.999,0.65).next(game);
        assertNotEquals(first.pieceId(),alternative.pieceId());
        engine.act(game,"a",alternative);assertEquals("BOARD",game.piece(alternative.pieceId()).status);
    }
    @Test void configurableWrongTriviaAnswerRemainsWithinAvailableOptions() {
        var question=catalog.question("classic-1");var pending=new GameState.PendingTrivia();
        pending.id="q";pending.playerId="a";pending.questionId=question.id();pending.options=question.options();
        game.trivia=pending;
        var wrong=new BotPolicy(engine,catalog,()->0.75,0.0).next(game);
        assertEquals("TRIVIA_ANSWER",wrong.type());
        assertNotEquals(question.correctIndex(),wrong.answerIndex());
        assertTrue(wrong.answerIndex()>=0 && wrong.answerIndex()<question.options().size());
    }
}
