package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.engine.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest extends EngineFixture {
    @Test void fourPiecesAndTwoServerDiceAndDoubleRelease() {
        assertEquals(12,game.pieces.size()); roll(); assertArrayEquals(new int[]{4,4},game.turn.dice);
        move(engine.calculate(game,"a","seat-0-piece-0",0));
        assertEquals(64,game.piece("seat-0-piece-0").position);
        assertTrue(game.turn.consumed[0]); assertFalse(game.turn.consumed[1]);
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        move(engine.calculate(game,"a","seat-0-piece-1",1));
        assertEquals("a",game.turn.playerId); assertEquals(2,game.turn.number); assertFalse(game.turn.rolled);
    }
    @Test void rejectInventedDiceDestinationOriginAndRepeatedRoll() {
        roll(); var m=engine.calculate(game,"a","seat-0-piece-0",0);
        assertThrows(GameException.class,()->engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("MOVE_REQUEST",m.pieceId(),0,6,null,64,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("MOVE_REQUEST",m.pieceId(),0,4,null,20,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("MOVE_REQUEST",m.pieceId(),0,4,2,64,null)));
        assertEquals("HOME",game.piece(m.pieceId()).status); assertFalse(game.turn.consumed[0]);
    }
    @Test void rejectsUnselectedDiceWithoutBuff() {
        assertThrows(GameException.class,()->engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(6,6))));
    }
    @Test void captureUsesPhysicalCellAndSafeCellsProtect() {
        place("seat-0-piece-0",3); place("seat-1-piece-0",55); // physical 6
        roll(); move(engine.calculate(game,"a","seat-0-piece-0",0)); // physical 6
        assertEquals("HOME",game.piece("seat-1-piece-0").status);
        place("seat-0-piece-1",2); place("seat-1-piece-1",54); // physical 5, safe
        move(engine.calculate(game,"a","seat-0-piece-1",1));
        assertEquals("BOARD",game.piece("seat-1-piece-1").status);
    }
    @Test void ownOccupancySafeCapacityAndExactCrowning() {
        place("seat-0-piece-0",2); place("seat-1-piece-0",54); place("seat-1-piece-1",54);
        roll(); assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        place("seat-0-piece-0",66); assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        place("seat-0-piece-0",64); move(engine.calculate(game,"a","seat-0-piece-0",0));
        assertEquals("CROWNED",game.piece("seat-0-piece-0").status);
    }
    @Test void victoryIsImmediateAndUnique() {
        for(int i=0;i<3;i++) place("seat-0-piece-"+i,68);
        place("seat-0-piece-3",64); roll(); move(engine.calculate(game,"a","seat-0-piece-3",0));
        assertEquals("FINISHED",game.status); assertEquals("a",game.winnerId);
        assertThrows(GameException.class,()->engine.act(game,"a",action("END_TURN",null,null,null,null,null,null)));
    }
    @Test void turnExpiryOrderAndRetirementAreServerControlled() {
        clock.now+=60_000; assertEquals("TURN_EXPIRED",engine.tick(game).getFirst().type());
        assertEquals("b",game.turn.playerId);
        place("seat-1-piece-0",5); engine.retire(game,"b");
        assertEquals("c",game.turn.playerId); assertEquals("HOME",game.piece("seat-1-piece-0").status);
        assertTrue(engine.retire(game,"b").isEmpty()); engine.retire(game,"c"); assertEquals("ABANDONED",game.status);
    }
    @Test void authenticationMembershipOwnershipAndTurnAreValidated() {
        assertThrows(GameException.class,()->engine.member(game,null));
        assertThrows(GameException.class,()->engine.member(game,"outsider"));
        assertThrows(GameException.class,()->engine.act(game,"b",action("ROLL_DICE",null,null,null,null,null,null)));
        roll(); assertThrows(GameException.class,()->engine.calculate(game,"a","seat-1-piece-0",0));
    }
    @Test void noLegalMovesAutomaticallyAdvancesNonDoubleTurn() {
        var other=new RuleEngine(board,new DiceSource(){ int n; public int roll(int m){ return ++n; }},clock,60_000);
        other.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null));
        assertEquals("b",game.turn.playerId);
    }
    @Test void backendRandomAlwaysInRequestedRange() {
        var random=new SecureDiceSource();
        for(int i=0;i<500;i++) { int value=random.roll(6); assertTrue(value>=1&&value<=6); value=random.roll(3); assertTrue(value>=1&&value<=3); }
    }
    @Test void secondDieCanBeUsedFirstAndOwnOrdinaryDestinationIsBlocked() {
        roll();move(engine.calculate(game,"a","seat-0-piece-0",1));
        assertTrue(game.turn.consumed[1]);assertFalse(game.turn.consumed[0]);
        place("seat-0-piece-1",4); // physical3, destination of released piece using4
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        game.piece("seat-0-piece-1").home();move(engine.calculate(game,"a","seat-0-piece-0",0));
        assertEquals(3,game.piece("seat-0-piece-0").position);assertEquals(2,game.turn.number);
    }
    @Test void doublesAreRequiredToReleaseAndCrownedPiecesCannotMove() {
        game.turn.rolled=true;game.turn.dice=new int[]{2,3};
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        place("seat-0-piece-0",68);
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
    }
}
