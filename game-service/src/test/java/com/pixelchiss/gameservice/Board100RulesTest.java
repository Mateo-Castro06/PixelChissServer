package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** Rules at physical boundaries, including shared starts and all four private lanes. */
class Board100RulesTest extends EngineFixture {
    static Stream<Arguments> boards() {
        return Stream.of("classic","science").flatMap(map -> IntStream.range(0,4).mapToObj(s -> Arguments.of(map,s)));
    }
    void start(String map,int seat,boolean trivia) {
        if (trivia) engine=new TriviaRules(board,m->1,clock,60_000,new SeedCatalog(),15_000,15_000);
        game=engine.create(new GameSetup("g","l",map,List.of(
                new GameSetup.Participant("a",false,seat),
                new GameSetup.Participant("b",false,(seat+1)%4)),"a"));
    }
    String id(int seat,int number) { return "seat-"+seat+"-piece-"+number; }
    void dice(int a,int b,boolean doubles) {
        game.turn.rolled=true;game.turn.dice=new int[]{a,b};game.turn.doubles=doubles;
        game.turn.consumed=new boolean[2];
    }
    void assertHome(GameState.Piece p) {
        assertEquals("HOME",p.status);assertNull(p.position);assertEquals(-1,p.progress);
    }
    @ParameterizedTest @MethodSource("boards")
    void releaseRequiresDoublesUsesNullOriginAndSharedSafeCapacity(String map,int seat) {
        start(map,seat,false);
        assertEquals(4,game.pieces.stream().filter(p->p.ownerId.equals("a")).count());
        game.pieces.forEach(this::assertHome);
        dice(2,3,false);
        assertEquals("DOUBLES_REQUIRED",assertThrows(GameException.class,
                ()->engine.calculate(game,"a",id(seat,0),0)).code());
        dice(2,2,true);
        var options=engine.legalMoves(game,"a");
        assertEquals(8,options.size());
        options.forEach(m->{assertNull(m.from());assertEquals(seat*17,m.to());assertEquals(0,m.progress());assertTrue(m.safe());});
        move(engine.calculate(game,"a",id(seat,0),0));
        move(engine.calculate(game,"a",id(seat,1),1));
        dice(2,2,true);
        assertEquals("CELL_FULL",assertThrows(GameException.class,
                ()->engine.calculate(game,"a",id(seat,2),0)).code());
        move(engine.calculate(game,"a",id(seat,0),0)); // free a slot
        move(engine.calculate(game,"a",id(seat,2),1));
        assertEquals(seat*17,game.piece(id(seat,2)).position);
        assertHome(game.piece(id(seat,3)));
    }
    @ParameterizedTest @MethodSource("boards")
    void fullCommonLapAndAllEightFinalCellsAreTraversedInOrder(String map,int seat) {
        start(map,seat,false);
        dice(1,1,true);
        move(engine.calculate(game,"a",id(seat,0),0));
        for(int progress=1;progress<=76;progress++) {
            dice(1,1,true);
            var m=engine.calculate(game,"a",id(seat,0),0);
            int expected=progress<=68?(seat*17+progress)%68:68+seat*8+progress-69;
            assertEquals(expected,m.to());
            move(m);
        }
        assertEquals("CROWNED",game.piece(id(seat,0)).status);
        assertEquals("CROWNED_PIECE",assertThrows(GameException.class,
                ()->engine.calculate(game,"a",id(seat,0),1)).code());
    }
    @ParameterizedTest @MethodSource("boards")
    void capturesAndProtectedStartRespectPhysicalCellsAndReturnHome(String map,int seat) {
        start(map,seat,false);
        String own=id(seat,0),rival=id((seat+1)%4,0);
        int start=seat*17;
        place(own,0);placeAt(rival,(start+1)%68);dice(1,2,false);
        var m=engine.calculate(game,"a",own,0);
        assertTrue(m.capture());
        var events=engine.act(game,"a",action("MOVE_REQUEST",own,0,1,m.from(),m.to(),null));
        assertTrue(events.stream().anyMatch(e->e.type().equals("PIECE_CAPTURED")));
        assertHome(game.piece(rival));
        game.piece(own).home();placeAt(rival,start);dice(1,1,true);
        m=engine.calculate(game,"a",own,0);
        assertEquals(start,m.to());assertFalse(m.capture());
        move(m);assertEquals("BOARD",game.piece(rival).status);
        // Two occupants already fill the shared start, including the rival.
        dice(1,1,true);
        assertEquals("CELL_FULL",assertThrows(GameException.class,
                ()->engine.calculate(game,"a",id(seat,2),0)).code());
    }
    @ParameterizedTest @MethodSource("boards")
    void triviaUsesSectorOffsetEightAndMatchingMap(String map,int seat) {
        start(map,seat,true);
        for(int sector=0;sector<4;sector++) {
            game.trivia=null;game.card=null;
            int trigger=sector*17+8;
            placeAt(id(seat,0),trigger-1);dice(1,2,false);
            move(engine.calculate(game,"a",id(seat,0),0));
            assertNotNull(game.trivia);
            assertEquals(map,new SeedCatalog().question(game.trivia.questionId).mapId());
        }
    }
    @ParameterizedTest @MethodSource("boards")
    void captureCardsIgnoreStartProtectionAndPrivateLaneIsExcluded(String map,int seat) {
        for(String card:List.of("kill-next","kill-chosen")) {
            start(map,seat,true);
            String own=id(seat,0),rival=id((seat+1)%4,0),privatePiece=id((seat+1)%4,1);
            int target=(seat*17+17)%68;
            placeAt(own,target-1);placeAt(rival,target);place(privatePiece,69);
            var events=new ArrayList<RuleEngine.Event>();
            var rules=(TriviaRules)engine;
            rules.grant(game,new SeedCatalog().card(card),"a",events);
            if(game.card!=null) {
                var choice=game.card.choices.getFirst();
                events.addAll(rules.act(game,"a",new GameAction("CARD_TARGET","g","card",game.version,
                        choice.pieceId(),null,null,null,choice.targetPosition(),null,game.card.id,null,
                        choice.targetPieceId(),choice.targetPlayerId())));
            }
            assertHome(game.piece(rival));assertEquals("BOARD",game.piece(privatePiece).status);
            assertTrue(events.stream().anyMatch(e->e.type().equals(card.equals("kill-next")?"PIECE_CAPTURED":"CARD_PIECE_RETURNED")));
        }
    }
    @ParameterizedTest @MethodSource("boards")
    void advanceSafeSkipsFullSafeAndDoesNotMoveLanePiece(String map,int seat) {
        start(map,seat,true);
        place(id(seat,0),4);place(id(seat,1),69);
        int safe=seat*17+5;
        placeAt(id((seat+1)%4,0),safe);placeAt(id((seat+1)%4,1),safe);
        var rules=(TriviaRules)engine;
        rules.grant(game,new SeedCatalog().card("advance-safe"),"a",new ArrayList<>());
        assertEquals(List.of(new GameState.CardChoice(id(seat,0),null,null,seat*17+12)),game.card.choices);
        var choice=game.card.choices.getFirst();
        rules.act(game,"a",new GameAction("CARD_TARGET","g","safe",game.version,
                choice.pieceId(),null,null,null,choice.targetPosition(),null,game.card.id,null,null,null));
        assertEquals(12,game.piece(id(seat,0)).progress);
        assertEquals(69,game.piece(id(seat,1)).progress);
    }
    @ParameterizedTest @MethodSource("boards")
    void deathPenaltiesAndRetirementAlwaysRestoreHomeAndSkipKeepsOrder(String map,int seat) {
        start(map,seat,true);
        var rules=(TriviaRules)engine;
        var catalog=new SeedCatalog();
        place(id(seat,0),75);place(id(seat,1),76);
        var events=new ArrayList<RuleEngine.Event>();
        rules.grant(game,catalog.card("instant-death"),"a",events);
        assertHome(game.piece(id(seat,0)));assertEquals("CROWNED",game.piece(id(seat,1)).status);
        assertTrue(events.stream().anyMatch(e->e.type().equals("CARD_PIECE_RETURNED")));
        place(id(seat,0),69);
        rules.grant(game,catalog.card("doubles-death"),"a",new ArrayList<>());
        var rolled=rules.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null));
        assertHome(game.piece(id(seat,0)));
        assertTrue(rolled.stream().anyMatch(e->e.type().equals("CARD_PIECE_RETURNED")));
        rules.grant(game,catalog.card("skip-turn"),"a",new ArrayList<>());
        clock.now=game.turn.deadline;rules.tick(game);assertEquals("b",game.turn.playerId);
        clock.now=game.turn.deadline;rules.tick(game);assertEquals("b",game.turn.playerId);
        assertTrue(game.player("a").effects.isEmpty());
        rules.retire(game,"a");
        game.pieces.stream().filter(p->p.ownerId.equals("a")).forEach(this::assertHome);
    }
    @ParameterizedTest @MethodSource("boards")
    void formerExitIndicesAreOrdinaryCommonCellsAndSixteenIsNotSafe(String map,int seat) {
        for(int target:List.of(16,64,65,66,67)) {
            start(map,seat,false);
            String own=id(seat,0),rival=id((seat+1)%4,0);
            placeAt(own,target-1);placeAt(rival,target);dice(1,2,false);
            var m=engine.calculate(game,"a",own,0);
            assertEquals(target,m.to());assertTrue(m.capture());assertFalse(m.safe());
            move(m);assertHome(game.piece(rival));
        }
        for(int target:List.of(64,65,66,67)) {
            start(map,seat,true);
            placeAt(id((seat+1)%4,0),target);
            ((TriviaRules)engine).grant(game,new SeedCatalog().card("kill-chosen"),"a",new ArrayList<>());
            assertEquals(List.of(new GameState.CardChoice(null,null,null,target)),game.card.choices);
        }
    }
}
