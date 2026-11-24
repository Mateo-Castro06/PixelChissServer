package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** The entry safe is five common cells before the start; the next step enters the private lane. */
class LapEntryTest extends EngineFixture {
    static Stream<Arguments> boards() {
        return Stream.of("classic","science").flatMap(map -> IntStream.range(0,4).mapToObj(s -> Arguments.of(map,s)));
    }
    void start(String map,int seat,boolean cards) {
        engine=cards ? new TriviaRules(board,m->1,clock,60_000,new SeedCatalog(),15_000,15_000)
                : new RuleEngine(board,m->1,clock,60_000);
        game=engine.create(new GameSetup("g","l",map,List.of(
                new GameSetup.Participant("a",false,seat),new GameSetup.Participant("b",false,(seat+1)%4)),"a"));
    }
    String id(int seat,int n) { return "seat-"+seat+"-piece-"+n; }
    void dice(int value) {
        game.turn.rolled=true;game.turn.dice=new int[]{value,1};game.turn.consumed=new boolean[2];game.turn.doubles=false;
    }
    @ParameterizedTest @MethodSource("boards")
    void departureAndEntryAreDifferentAndLeadToThePrivateLane(String map,int seat) {
        start(map,seat,false);
        var piece=id(seat,0);
        place(piece,0);dice(1);
        int departure=game.piece(piece).position;
        assertEquals(((seat*17+5)+1)%68,engine.calculate(game,"a",piece,0).to());
        place(piece,63);
        assertNotEquals(departure,game.piece(piece).position);
        assertEquals(seat*17,game.piece(piece).position);
        assertTrue(Board.SAFE.contains(game.piece(piece).position));
        var m=engine.calculate(game,"a",piece,0);
        assertEquals(64,m.progress());assertEquals(68+seat*8,m.to());
        assertFalse(m.capture());assertFalse(m.crown());move(m);
        assertEquals("BOARD",game.piece(piece).status);
    }
    @ParameterizedTest @MethodSource("boards")
    void eachDieCrossesTheAccessSafeUsingOneStepPerEdge(String map,int seat) {
        for(int from=57;from<=63;from++) for(int value=1;value<=6;value++) {
            start(map,seat,false);
            var piece=id(seat,0);place(piece,from);dice(value);
            int next=from+value;
            var m=engine.calculate(game,"a",piece,0);
            assertEquals(next,m.progress());
            assertEquals(next<=63?((seat*17+5)+next)%68:68+seat*8+next-64,m.to());
            assertEquals(Board.SAFE.contains(m.to()),m.safe());
            move(m);assertEquals(next,game.piece(piece).progress);
        }
    }
    @ParameterizedTest @MethodSource("boards")
    void landingOnAccessHonorsSafeCapacityAndCrossingPreservesDestinationOnlyBlocking(String map,int seat) {
        start(map,seat,false);
        String piece=id(seat,0);int entry=seat*17;
        place(piece,62);place(id(seat,1),63);placeAt(id((seat+1)%4,0),entry);dice(1);
        assertEquals("CELL_FULL",assertThrows(GameException.class,()->engine.calculate(game,"a",piece,0)).code());
        dice(2); // crossing a full intermediate safe remains allowed, as before
        assertEquals(68+seat*8,engine.calculate(game,"a",piece,0).to());
        game.piece(id(seat,1)).home();dice(1);
        var m=engine.calculate(game,"a",piece,0);
        assertEquals(entry,m.to());assertTrue(m.safe());assertFalse(m.capture());move(m);
        assertEquals(63,game.piece(piece).progress);
        assertEquals("BOARD",game.piece(id((seat+1)%4,0)).status);
    }
    @ParameterizedTest @MethodSource("boards")
    void advanceSafeCanReachAccessButCannotRepeatItOrLeaveLane(String map,int seat) {
        start(map,seat,true);
        String piece=id(seat,0);place(piece,62);
        var rules=(TriviaRules)engine;var card=new SeedCatalog().card("advance-safe");
        var events=new ArrayList<RuleEngine.Event>();rules.grant(game,card,"a",events);
        assertEquals(List.of(new GameState.CardChoice(piece,null,null,(seat*17))),game.card.choices);
        rules.act(game,"a",new GameAction("CARD_TARGET","g","access-safe",game.version,
                piece,null,null,null,(seat*17),null,game.card.id,null,null,null));
        assertEquals(63,game.piece(piece).progress);
        assertEquals((seat*17),game.piece(piece).position);
        for(int progress:List.of(63,64,70)) {
            place(piece,progress);events.clear();rules.grant(game,card,"a",events);
            assertNull(game.card);assertEquals("CARD_DISCARDED",events.getFirst().type());
        }
    }
    @ParameterizedTest @MethodSource("boards")
    void nearestCaptureIncludesAccessAndStopsBeforeLane(String map,int seat) {
        start(map,seat,true);
        String piece=id(seat,0),rival=id((seat+1)%4,0);
        place(piece,62);placeAt(rival,(seat*17));
        var rules=(TriviaRules)engine;var card=new SeedCatalog().card("kill-next");
        var events=new ArrayList<RuleEngine.Event>();rules.grant(game,card,"a",events);
        assertEquals("HOME",game.piece(rival).status);assertNull(game.piece(rival).position);
        assertTrue(events.stream().anyMatch(e->e.type().equals("PIECE_CAPTURED")));
        assertEquals(62,game.piece(piece).progress);
        place(piece,63);placeAt(rival,((seat*17)+1)%68);events.clear();rules.grant(game,card,"a",events);
        assertEquals("BOARD",game.piece(rival).status);
        assertEquals("CARD_DISCARDED",events.getFirst().type());
    }
    @ParameterizedTest @MethodSource("boards")
    void reconnectionRestoresEntryProgressInsteadOfTreatingItAsDeparture(String map,int seat) {
        start(map,seat,false);
        String piece=id(seat,0);place(piece,63);
        activateHomeForMovement();
        game.player("a").disconnectedAt=clock.millis();
        var repository=new MemoryGames();repository.create(game,null);
        var service=new com.pixelchiss.gameservice.service.GameService(repository,engine,
                new com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints(repository.json));
        service.reconnect("g","a");
        assertEquals(63,service.load("g").piece(piece).progress);
        assertEquals((seat*17),service.load("g").piece(piece).position);
        var rolled=service.action("a",new GameAction("ROLL_DICE","g","restored-roll",1L,
                null,null,null,null,null,null,null,null,null,null));
        var beforeAck=service.load("g");
        var acknowledged=service.action("a",new GameAction("DICE_ANIMATION_COMPLETED","g","ack",beforeAck.version,
                null,null,null,null,null,null,beforeAck.turn.rollId,null,null,null));
        var m=service.moves("g","a").stream().filter(option->option.pieceId().equals(piece)).findFirst().orElseThrow();
        assertEquals(64,m.progress());assertEquals(68+seat*8,m.to());
        var result=service.action("a",new GameAction("MOVE_REQUEST","g","restored-move",acknowledged.committedVersion(),
                piece,m.dieIndex(),m.dieValue(),m.from(),m.to(),null,null,null,null,null));
        assertEquals(64,result.state().pieces().stream().filter(p->p.id.equals(piece)).findFirst().orElseThrow().progress);
        assertEquals("v5-100",service.load("g").boardLayout);
    }
}
