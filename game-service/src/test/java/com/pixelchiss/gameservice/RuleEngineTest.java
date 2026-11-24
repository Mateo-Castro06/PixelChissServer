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
        assertEquals(5,game.piece("seat-0-piece-0").position);
        assertArrayEquals(new boolean[]{true,true},game.turn.consumed);
        assertTrue(engine.legalMoves(game,"a").isEmpty());
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        assertEquals("a",game.turn.playerId); assertEquals(1,game.turn.number); assertFalse(game.turn.rolled);
    }
    @Test void rejectInventedDiceDestinationOriginAndRepeatedRoll() {
        rollForMovement(); var m=engine.calculate(game,"a","seat-0-piece-0",0);
        assertThrows(GameException.class,()->engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("MOVE_REQUEST",m.pieceId(),0,6,null,0,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("MOVE_REQUEST",m.pieceId(),0,4,null,20,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("MOVE_REQUEST",m.pieceId(),0,4,2,0,null)));
        assertEquals("BOARD",game.piece(m.pieceId()).status); assertFalse(game.turn.consumed[0]);
    }
    @Test void rejectsUnselectedDiceWithoutBuff() {
        assertThrows(GameException.class,()->engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(6,6))));
    }
    @Test void captureUsesPhysicalCellAndSafeCellsProtect() {
        placeAt("seat-0-piece-0",15);placeAt("seat-1-piece-0",23);rollForMovement();
        move(engine.calculate(game,"a","seat-0-piece-0",2));
        assertEquals("HOME",game.piece("seat-1-piece-0").status);
        placeAt("seat-0-piece-1",8);placeAt("seat-1-piece-1",12);
        game.turn.rolled=true;game.turn.dice=new int[]{4,4};game.turn.consumed=new boolean[2];
        move(engine.calculate(game,"a","seat-0-piece-1",0));
        assertEquals("BOARD",game.piece("seat-1-piece-1").status);
    }
    @Test void ownOccupancySafeCapacityAndExactCrowning() {
        placeAt("seat-0-piece-0",8); placeAt("seat-1-piece-0",12); placeAt("seat-1-piece-1",12);
        rollForMovement(); assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        place("seat-0-piece-0",Board.FINISH-2); assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        place("seat-0-piece-0",Board.FINISH-4); move(engine.calculate(game,"a","seat-0-piece-0",0));
        assertEquals("CROWNED",game.piece("seat-0-piece-0").status);
    }
    @Test void victoryIsImmediateAndUnique() {
        for(int i=0;i<3;i++) place("seat-0-piece-"+i,Board.FINISH);
        place("seat-0-piece-3",Board.FINISH-4); rollForMovement(); move(engine.calculate(game,"a","seat-0-piece-3",0));
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
        rollForMovement(); assertThrows(GameException.class,()->engine.calculate(game,"a","seat-1-piece-0",0));
    }
    @Test void noLegalMovesAutomaticallyAdvancesNonDoubleTurn() {
        var other=new RuleEngine(board,new DiceSource(){ int n; public int roll(int m){ return n++%2+2; }},clock,60_000);
        for(int attempt=0;attempt<3;attempt++) {
            other.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null));
            other.act(game,"a",new GameAction("DICE_ANIMATION_COMPLETED","g","ack"+attempt,game.version,
                null,null,null,null,null,null,game.turn.rollId,null,null,null));
        }
        assertEquals("b",game.turn.playerId);
    }
    @Test void backendRandomAlwaysInRequestedRange() {
        var random=new SecureDiceSource();
        for(int i=0;i<500;i++) { int value=random.roll(6); assertTrue(value>=1&&value<=6); value=random.roll(3); assertTrue(value>=1&&value<=3); }
    }
    @Test void secondDieCanBeUsedFirstAndOwnOrdinaryDestinationIsBlocked() {
        rollForMovement();move(engine.calculate(game,"a","seat-0-piece-0",1));
        assertTrue(game.turn.consumed[1]);assertFalse(game.turn.consumed[0]);
        place("seat-0-piece-1",8);
        var movement=engine.calculate(game,"a","seat-0-piece-0",0);assertFalse(movement.capture());move(movement);
        assertEquals(13,game.piece("seat-0-piece-0").position);
    }
    @Test void doublesAreRequiredToReleaseAndCrownedPiecesCannotMove() {
        game.turn.rolled=true;game.turn.dice=new int[]{2,3};
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
        place("seat-0-piece-0",Board.FINISH);
        assertThrows(GameException.class,()->engine.calculate(game,"a","seat-0-piece-0",0));
    }
}
