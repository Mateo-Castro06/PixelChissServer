package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.bot.BotPolicy;
import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CaptureRollLockTest extends EngineFixture {
    String own(int n) { return "seat-0-piece-"+n; }
    String rival(int n) { return "seat-1-piece-"+n; }
    void setup(String map,int a,int b,boolean anotherPiece) {
        int[] draws={a,b};int[] index={0};
        engine=new RuleEngine(board,m->draws[index[0]++%2],clock,30_000);
        game=engine.create(new GameSetup("g","l",map,List.of(
                new GameSetup.Participant("a",false,0),new GameSetup.Participant("b",false,1)),"a"));
        placeAt(own(0),6);
        if(anotherPiece) placeAt(own(1),20);else place(own(1),Board.FINISH);
        place(own(2),Board.FINISH);place(own(3),Board.FINISH);
    }
    List<RuleEngine.Event> accept(String id,int die) {
        var m=engine.calculate(game,"a",id,die);
        return engine.act(game,"a",action("MOVE_REQUEST",id,die,m.dieValue(),m.from(),m.to(),null));
    }
    @ParameterizedTest @CsvSource({"classic,0","classic,1","science,0","science,1"})
    void capturingPieceCannotUseOtherDieButAnotherPieceCan(String map,int firstDie) {
        int[] draws={4,3};setup(map,4,3,true);
        placeAt("seat-1-piece-0",6+draws[firstDie]);
        roll();long deadline=game.turn.deadline;
        move(engine.calculate(game,"a","seat-0-piece-0",firstDie));
        assertEquals("HOME",game.piece("seat-1-piece-0").status);
        assertEquals(Set.of(own(0)),game.turn.captureLockedPieceIds);
        assertEquals("PIECE_CAPTURE_LOCKED",assertThrows(GameException.class,
                ()->engine.calculate(game,"a","seat-0-piece-0",1-firstDie)).code());
        var remaining=engine.legalMoves(game,"a");
        assertFalse(remaining.isEmpty());
        assertTrue(remaining.stream().allMatch(m->m.pieceId().equals("seat-0-piece-1")&&m.dieIndex()==1-firstDie));
        assertEquals(deadline,game.turn.deadline);
        move(remaining.getFirst());assertEquals("b",game.turn.playerId);
    }
    @ParameterizedTest @CsvSource({"classic,0","classic,1","science,0","science,1"})
    void onlyCapturingPieceCannotUseRemainingDieSoRollClosesImmediately(String map,int firstDie) {
        setup(map,4,3,false);placeAt(rival(0),6+(firstDie==0?4:3));roll();
        var old=game.turn;var events=accept(own(0),firstDie);
        assertArrayEquals(new boolean[]{true,true},old.consumed);
        assertEquals(6+(firstDie==0?4:3),game.piece(own(0)).position);
        assertEquals("b",game.turn.playerId);assertNull(game.turn.movementStartedAt);
        assertNull(game.turn.rollId);assertTrue(game.turn.captureLockedPieceIds.isEmpty());
        assertTrue(events.stream().anyMatch(e->e.type().equals("TURN_CHANGED")));
        assertTrue(events.stream().noneMatch(e->Set.of("PIECE_BLOWN","TURN_EXPIRED").contains(e.type())));
        assertTrue(engine.tick(game).isEmpty());
    }
    @Test void remainingLegalCapturesExcludeLockedPieceAndCanBeTakenByAnotherPiece() {
        setup("classic",4,3,true);placeAt(rival(0),10);placeAt(rival(1),13);placeAt(rival(2),23);roll();
        accept(own(0),0);
        var captures=engine.legalMoves(game,"a").stream().filter(RuleEngine.Move::capture).toList();
        assertEquals(List.of(own(1)),captures.stream().map(RuleEngine.Move::pieceId).toList());
        var events=accept(own(1),1);
        assertEquals("HOME",game.piece(rival(2)).status);assertEquals("BOARD",game.piece(rival(1)).status);
        assertTrue(events.stream().noneMatch(e->e.type().equals("PIECE_BLOWN")));
    }
    @Test void omissionBeforeAnyCaptureStillBlowsOnlyTheLegalCandidate() {
        setup("classic",4,3,true);placeAt(rival(0),10);roll();
        assertEquals(Set.of(own(0)),game.turn.captureCandidates);
        var events=engine.act(game,"a",action("END_TURN",null,null,null,null,null,null));
        assertEquals("HOME",game.piece(own(0)).status);
        assertEquals(1,events.stream().filter(e->e.type().equals("PIECE_BLOWN")).count());
    }
    @Test void doublesDiscardUnavailableRemainderAndClearLockOnNextRoll() {
        setup("classic",4,4,false);placeAt(rival(0),10);roll();String first=game.turn.rollId;
        var events=accept(own(0),0);
        assertTrue(game.turn.extraRollAvailable);assertFalse(game.turn.rolled);
        assertArrayEquals(new boolean[]{true,true},game.turn.consumed);assertNull(game.turn.movementStartedAt);
        assertEquals(Set.of(own(0)),game.turn.captureLockedPieceIds);
        assertEquals(1,game.turn.consecutiveDoubles);
        assertTrue(events.stream().anyMatch(e->e.type().equals("ROLL_AVAILABLE")));
        roll();assertNotEquals(first,game.turn.rollId);assertTrue(game.turn.captureLockedPieceIds.isEmpty());
        assertTrue(engine.legalMoves(game,"a").stream().anyMatch(m->m.pieceId().equals(own(0))));
        placeAt(rival(1),14);assertTrue(engine.calculate(game,"a",own(0),0).capture());accept(own(0),0);
        assertEquals("HOME",game.piece(rival(1)).status);assertEquals(2,game.turn.consecutiveDoubles);
        roll();assertTrue(game.turn.crownChoiceRequired);assertTrue(game.turn.captureLockedPieceIds.isEmpty());
        engine.act(game,"a",action("CROWN_REQUEST",own(0),null,null,null,null,null));
        assertEquals("FINISHED",game.status);assertThrows(GameException.class,this::roll);
    }
    @Test void partialHomeReleaseCaptureKeepsCountersAndThirdPairTurnClosure() {
        setup("science",5,5,true);game.piece(own(0)).home();placeAt(own(1),6);placeAt(rival(0),11);
        roll();assertEquals(Set.of(own(0)),game.turn.releasedPieceIds);
        assertArrayEquals(new boolean[]{true,false},game.turn.consumed);
        accept(own(1),1);assertEquals(1,game.turn.consecutiveDoubles);assertTrue(game.turn.extraRollAvailable);
        assertEquals(Set.of(own(1)),game.turn.captureLockedPieceIds);
        roll();assertTrue(game.turn.captureLockedPieceIds.isEmpty());
        assertTrue(engine.legalMoves(game,"a").stream().anyMatch(m->m.pieceId().equals(own(1))));
        accept(own(1),2);assertEquals(2,game.turn.consecutiveDoubles);
        roll();assertTrue(game.turn.crownChoiceRequired);assertTrue(engine.legalMoves(game,"a").isEmpty());
        engine.act(game,"a",action("CROWN_REQUEST",own(1),null,null,null,null,null));
        assertEquals("b",game.turn.playerId);assertEquals(0,game.turn.consecutiveDoubles);
        assertEquals("NOT_YOUR_TURN",assertThrows(GameException.class,this::roll).code());
    }
    @Test void protectedCellsDoNotCreateCaptureLocksAndCardCapturesKeepTheirPolicy() {
        setup("classic",4,3,true);placeAt(own(0),8);placeAt(rival(0),12);roll();
        assertFalse(engine.calculate(game,"a",own(0),0).capture());accept(own(0),0);
        assertTrue(game.turn.captureLockedPieceIds.isEmpty());
        assertNotNull(engine.calculate(game,"a",own(0),1));
        setup("classic",4,3,true);placeAt(own(0),11);placeAt(rival(0),12);roll();
        var rules=new TriviaRules(board,m->1,clock,30_000,new SeedCatalog(),15_000,15_000);
        var events=new ArrayList<RuleEngine.Event>();rules.grant(game,new SeedCatalog().card("kill-next"),"a",events);
        assertEquals("HOME",game.piece(rival(0)).status);assertTrue(game.turn.captureLockedPieceIds.isEmpty());
        assertTrue(events.stream().anyMatch(e->e.type().equals("PIECE_CAPTURED")));
        assertNotNull(engine.calculate(game,"a",own(0),0));
    }
    @Test void multipleVictimsLockOneAttackerAndSumCaptureConsumesBothDice() {
        setup("classic",4,3,true);placeAt(rival(0),10);placeAt(rival(1),10);roll();
        var events=accept(own(0),0);assertEquals(Set.of(own(0)),game.turn.captureLockedPieceIds);
        assertEquals(2,events.stream().filter(e->e.type().equals("PIECE_CAPTURED")).count());
        setup("classic",4,3,false);placeAt(rival(0),13);roll();var old=game.turn;
        accept(own(0),2);assertArrayEquals(new boolean[]{true,true},old.consumed);
        assertEquals("HOME",game.piece(rival(0)).status);assertEquals("b",game.turn.playerId);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void botsUseOtherPieceOrImmediatelyFinishWithoutRetryLoop(boolean anotherPiece) {
        setup("science",4,3,anotherPiece);game.player("a").bot=true;placeAt(rival(0),10);
        var bot=new BotPolicy(engine,new SeedCatalog(),()->0.0);
        engine.act(game,"a",bot.next(game));var capture=bot.next(game);
        assertEquals(own(0),capture.pieceId());assertEquals(0,capture.dieIndex());
        engine.act(game,"a",capture);
        if(anotherPiece) {
            var next=bot.next(game);assertEquals("MOVE_REQUEST",next.type());assertEquals(own(1),next.pieceId());
            assertEquals(1,next.dieIndex());engine.act(game,"a",next);
        }
        assertEquals("b",game.turn.playerId);assertNull(bot.next(game));
    }
    @Test void captureOnTriviaPausesFifteenSecondsAndResumesOnlyRemainingTime() {
        setup("science",4,3,true);placeAt(own(0),5);placeAt(rival(0),8);
        var catalog=new SeedCatalog(){@Override public List<CardDefinition> cards(String map,String type){return List.of();}};
        engine=new TriviaRules(board,m->1,clock,30_000,catalog,15_000,15_000);
        game.player("a").effects.add(new GameState.Effect("CHOOSE_DICE",1,1,6));
        engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,List.of(4,3)));
        engine.act(game,"a",new GameAction("DICE_ANIMATION_COMPLETED","g","ack",game.version,
                null,null,null,null,null,null,game.turn.rollId,null,null,null));
        clock.now+=7_000;accept(own(0),1);
        assertNotNull(game.trivia);assertEquals(clock.now+15_000,game.trivia.deadline);
        assertEquals(23_000,game.turn.movementRemainingMillis);assertEquals(Set.of(own(0)),game.turn.captureLockedPieceIds);
        clock.now+=5_000;
        var events=engine.act(game,"a",new GameAction("TRIVIA_ANSWER","g","answer",game.version,
                null,null,null,null,null,null,game.trivia.id,0,null,null));
        assertEquals(clock.now+23_000,game.turn.deadline);
        assertTrue(events.stream().anyMatch(e->e.type().equals("MOVEMENT_TIMER_RESUMED")));
        assertEquals("PIECE_CAPTURE_LOCKED",assertThrows(GameException.class,()->engine.calculate(game,"a",own(0),0)).code());
        assertTrue(engine.legalMoves(game,"a").stream().allMatch(m->m.pieceId().equals(own(1))));
    }
}
