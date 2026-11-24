package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.bot.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefinitiveRulesTest extends EngineFixture {
    String own(int n) { return "seat-0-piece-"+n; }
    String rival(int n) { return "seat-1-piece-"+n; }
    void source(int... values) {
        int[] i={0}; engine=new RuleEngine(board,m->values[i[0]++ % values.length],clock,30_000);
        create();
    }
    void rawRoll() { engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null)); }
    GameAction ack(String id) { return new GameAction("DICE_ANIMATION_COMPLETED","g",UUID.randomUUID().toString(),game.version,
            null,null,null,null,null,null,id,null,null,null); }
    void confirm() { engine.act(game,"a",ack(game.turn.rollId)); }
    @Test void animationIsSeparateFromMovementAndDuplicatesDoNotExtend() {
        source(2,3);place(own(0),10);rawRoll();
        assertNull(game.turn.movementStartedAt);assertEquals(clock.now+10_000,game.turn.animationDeadline);
        assertTrue(engine.legalMoves(game,"a").isEmpty());
        assertEquals("ANIMATION_PENDING",assertThrows(GameException.class,
                ()->move(engine.calculate(game,"a",own(0),0))).code());
        var confirmation=ack(game.turn.rollId);
        assertEquals("NOT_YOUR_TURN",assertThrows(GameException.class,()->engine.act(game,"b",confirmation)).code());
        assertEquals("STALE_ROLL",assertThrows(GameException.class,()->engine.act(game,"a",ack("old"))).code());
        clock.now+=9000;engine.act(game,"a",confirmation);long deadline=game.turn.deadline;
        assertEquals(clock.now+30_000,deadline);clock.now+=1000;engine.act(game,"a",ack(game.turn.rollId));
        assertEquals(deadline,game.turn.deadline);
    }
    @Test void timeoutPreservesAcceptedMovesDiscardsDiceAndClosesOnce() {
        source(2,3);place(own(0),10);place(own(1),20);rawRoll();confirm();
        move(engine.calculate(game,"a",own(0),0));
        int accepted=game.piece(own(0)).progress, untouched=game.piece(own(1)).progress;
        clock.now=game.turn.deadline-1;assertTrue(engine.tick(game).isEmpty());
        clock.now++;assertEquals("TURN_EXPIRED",assertThrows(GameException.class,
                ()->move(engine.calculate(game,"a",own(1),1))).code());
        var events=engine.tick(game);
        assertEquals(List.of(1),events.getFirst().data().get("discardedDice"));
        assertEquals(accepted,game.piece(own(0)).progress);assertEquals(untouched,game.piece(own(1)).progress);
        assertEquals("b",game.turn.playerId);assertTrue(engine.tick(game).isEmpty());
    }
    @Test void unconfirmedTimeoutNeverBlowsAndLateConfirmationCannotReactivate() {
        source(2,3);placeAt(own(0),6);placeAt(rival(0),8);rawRoll();var late=ack(game.turn.rollId);
        clock.now=game.turn.animationDeadline;var events=engine.tick(game);
        assertEquals("ANIMATION_TIMEOUT",events.getFirst().data().get("reason"));
        assertEquals("BOARD",game.piece(own(0)).status);assertEquals("b",game.turn.playerId);
        assertThrows(GameException.class,()->engine.act(game,"a",late));
    }
    @Test void timeoutBlowsOnlyThePieceWithUnfulfilledOpportunity() {
        source(2,3);placeAt(own(0),6);placeAt(rival(0),8);rawRoll();confirm();
        clock.now=game.turn.deadline;var events=engine.tick(game);
        assertTrue(events.stream().anyMatch(e->e.type().equals("PIECE_BLOWN")));
        assertEquals("HOME",game.piece(own(0)).status);assertNull(game.piece(own(0)).position);
    }
    @Test void oneCaptureSatisfiesEntireTurnAndCapturesAllRivals() {
        source(2,3);placeAt(own(0),6);placeAt(rival(0),8);placeAt(rival(1),8);
        placeAt(own(1),7);placeAt(rival(2),10);rawRoll();confirm();
        move(engine.calculate(game,"a",own(0),0));assertTrue(game.turn.captureSatisfied);
        assertEquals("HOME",game.piece(rival(0)).status);assertEquals("HOME",game.piece(rival(1)).status);
        engine.act(game,"a",action("END_TURN",null,null,null,null,null,null));
        assertEquals("BOARD",game.piece(own(1)).status);
    }
    @Test void ambiguousBlowAndExternallyRemovedVictimNeverPickArbitraryPiece() {
        source(2,3);placeAt(own(0),6);placeAt(own(1),6);placeAt(rival(0),8);rawRoll();confirm();
        clock.now=game.turn.deadline;var events=engine.tick(game);
        assertTrue(events.stream().anyMatch(e->e.type().equals("BLOW_AMBIGUOUS")));
        assertEquals("BOARD",game.piece(own(0)).status);
        source(2,3);placeAt(own(0),6);placeAt(rival(0),8);rawRoll();confirm();game.piece(rival(0)).home();
        clock.now=game.turn.deadline;assertFalse(engine.tick(game).stream().anyMatch(e->e.type().equals("PIECE_BLOWN")));
    }
    @Test void sumConsumesBothAndCannotBeReusedWhileSplitCanMoveSamePieceTwice() {
        source(2,3);place(own(0),10);rawRoll();confirm();
        move(engine.calculate(game,"a",own(0),2));assertEquals(15,game.piece(own(0)).progress);
        assertEquals("b",game.turn.playerId);
        source(2,3);place(own(0),10);rawRoll();confirm();move(engine.calculate(game,"a",own(0),1));
        assertEquals("DIE_UNAVAILABLE",assertThrows(GameException.class,()->engine.calculate(game,"a",own(0),2)).code());
        move(engine.calculate(game,"a",own(0),0));assertEquals(15,game.piece(own(0)).progress);
    }
    @Test void threeHomeAttemptsIncludeThreeCrownedAndOneHome() {
        source(2,3);for(int i=0;i<3;i++)place(own(i),Board.FINISH);
        for(int attempt=1;attempt<=3;attempt++) {
            rawRoll();assertEquals(attempt,game.turn.homeAttempts);confirm();
            assertEquals(attempt<3?"a":"b",game.turn.playerId);
        }
    }
    @Test void everyDoubleReleasesCorrectCountAndSpecialCaptureIsAtomic() {
        for(int value=1;value<=6;value++) {
            source(value,value);placeAt(rival(0),5);rawRoll();
            int count=value==1?4:value==6?3:2;
            assertEquals(count,game.pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("BOARD")).count());
            assertEquals("HOME",game.piece(rival(0)).status);
            assertThrows(GameException.class,()->rawRoll());
        }
        source(3,3);placeAt(own(0),15);placeAt(rival(0),5);rawRoll();
        assertEquals(3,game.pieces.stream().filter(p->p.ownerId.equals("a")&&p.status.equals("BOARD")).count());
    }
    @Test void ownStacksAreLegalNormalSafeAndStartCapacitiesAreDifferent() {
        source(2,3);placeAt(own(0),6);for(int i=1;i<4;i++)placeAt(own(i),8);rawRoll();confirm();
        var move=engine.calculate(game,"a",own(0),0);assertFalse(move.capture());move(move);
        assertEquals(4,game.occupants(8,null).size());
        source(2,3);placeAt(own(0),10);placeAt(rival(0),12);placeAt(rival(1),12);rawRoll();confirm();
        assertEquals("CELL_FULL",assertThrows(GameException.class,()->engine.calculate(game,"a",own(0),0)).code());
        source(2,3);placeAt(own(0),20);for(int i=0;i<4;i++)placeAt(rival(i),22);rawRoll();confirm();
        assertEquals("CELL_FULL",assertThrows(GameException.class,()->engine.calculate(game,"a",own(0),0)).code());
        game.piece(rival(3)).home();assertFalse(engine.calculate(game,"a",own(0),0).capture());
    }
    @Test void thirdDoubleSelectsOwnPreviouslyActivePieceOnly() {
        source(2,2);place(own(0),Board.FIRST_FINISH_PROGRESS);game.turn.consecutiveDoubles=2;rawRoll();confirm();
        assertTrue(game.turn.crownChoiceRequired);
        assertThrows(GameException.class,()->engine.act(game,"a",action("CROWN_REQUEST",own(1),null,null,null,null,null)));
        assertThrows(GameException.class,()->engine.act(game,"a",action("CROWN_REQUEST",rival(0),null,null,null,null,null)));
        engine.act(game,"a",action("CROWN_REQUEST",own(0),null,null,null,null,null));
        assertEquals("CROWNED",game.piece(own(0)).status);
        assertThrows(GameException.class,()->engine.act(game,"a",action("CROWN_REQUEST",own(0),null,null,null,null,null)));
    }
    @Test void botStartsMovementWithoutGodotAndPrioritizesCapture() {
        source(2,3);game.player("a").bot=true;placeAt(own(0),6);placeAt(rival(0),8);rawRoll();
        assertNotNull(game.turn.movementStartedAt);assertEquals("COMPLETED",game.turn.animationStatus);
        var bot=new BotPolicy(engine,new SeedCatalog(),()->0.5,0.5);
        var action=bot.next(game);assertTrue(engine.calculate(game,"a",action.pieceId(),action.dieIndex()).capture());
    }
    @Test void probabilityEndpointsWeightsStatisticsAndAnswerOrder() {
        for(int n:List.of(2,3,4,7)) for(int correct=0;correct<n;correct++) for(double p:new double[]{0,1,.2,.6}) {
            assertEquals(1,Arrays.stream(TriviaSelection.weights(n,correct,p)).sum(),1e-12);
            Random random=new Random(1234+correct);int hits=0;int[] counts=new int[n];int trials=20_000;
            for(int i=0;i<trials;i++){int answer=TriviaSelection.select(n,correct,p,random::nextDouble);counts[answer]++;if(answer==correct)hits++;}
            assertEquals(p,(double)hits/trials,.018);
            for(int i=0;i<n;i++) if(i!=correct) assertEquals((1-p)/(n-1),(double)counts[i]/trials,.018);
        }
        assertThrows(IllegalArgumentException.class,()->TriviaSelection.weights(4,0,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->TriviaSelection.weights(1,0,.5));
        assertThrows(IllegalArgumentException.class,()->TriviaSelection.select(4,0,.5,()->1));
    }
}
