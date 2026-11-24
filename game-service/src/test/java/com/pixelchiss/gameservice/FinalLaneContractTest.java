package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.application.*;
import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.engine.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit physical coordinates, independent of Board.position's arithmetic. */
class FinalLaneContractTest extends EngineFixture {
    static Stream<Arguments> colors() {
        return Stream.of("classic","science").flatMap(map->Stream.of(
                Arguments.of(map,0,5,List.of(2,3,4,5,68,69,70),75),
                Arguments.of(map,1,22,List.of(19,20,21,22,76,77,78),83),
                Arguments.of(map,2,39,List.of(36,37,38,39,84,85,86),91),
                Arguments.of(map,3,56,List.of(53,54,55,56,92,93,94),99)));
    }
    void start(String map,int seat,int progress,int die) {
        engine=new RuleEngine(board,m->1,clock,30_000);
        game=engine.create(new GameSetup("g","l",map,List.of(
                new GameSetup.Participant("a",false,seat),new GameSetup.Participant("b",false,(seat+1)%4)),"a"));
        place("seat-"+seat+"-piece-0",progress);
        game.turn.rolled=true;game.turn.dice=new int[]{die,1};game.turn.consumed=new boolean[]{false,true};
        game.turn.animationStatus="COMPLETED";game.turn.movementStartedAt=clock.now;game.turn.deadline=clock.now+30_000;
    }
    @ParameterizedTest @MethodSource("colors")
    void oneToFourStepsEnterOwnLaneAndSerializeTheAuthoritativeDestination(String map,int seat,int exit,List<Integer> path,int crown) {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        for(int distance=1;distance<=4;distance++) for(int extra=0;extra<=2;extra++) {
            int progress=69-distance,die=distance+extra;
            start(map,seat,progress,die);String piece="seat-"+seat+"-piece-0";
            var route=board.route(seat,0);
            assertEquals(exit,route.get(0));assertEquals(exit,route.get(68));
            assertEquals(path,route.subList(65,72));assertEquals(crown,route.get(76));
            var expected=path.subList(5-distance,5+extra);
            assertEquals(die,expected.size());
            assertEquals(expected,route.subList(progress+1,progress+die+1));
            var movement=engine.calculate(game,"a",piece,0);assertEquals(expected.getLast(),movement.to());
            var events=engine.act(game,"a",action("MOVE_REQUEST",piece,0,die,movement.from(),movement.to(),null));
            var wire=json.readTree(json.writeValueAsString(new GameUpdate("GAME_UPDATED","g",1,"move",events,GameSnapshot.of(game))));
            var serializedPiece=StreamSupport.stream(wire.path("state").path("pieces").spliterator(),false)
                    .filter(p->piece.equals(p.path("id").asString())).findFirst().orElseThrow();
            assertEquals(path.get(4-distance),wire.path("events").get(0).path("data").path("from").asInt());
            assertEquals(expected.getLast().intValue(),wire.path("events").get(0).path("data").path("to").asInt());
            assertEquals(69+extra,serializedPiece.path("progress").asInt());
            assertEquals(expected.getLast().intValue(),serializedPiece.path("position").asInt());
        }
    }
    @ParameterizedTest @MethodSource("colors")
    void exactCrownAndOvershootAreEnforcedForEveryColor(String map,int seat,int exit,List<Integer> path,int crown) {
        String piece="seat-"+seat+"-piece-0";
        start(map,seat,75,3);
        assertEquals("OVERSHOOT",assertThrows(GameException.class,()->engine.calculate(game,"a",piece,0)).code());
        start(map,seat,73,3);var move=engine.calculate(game,"a",piece,0);
        assertEquals(crown,move.to());assertTrue(move.crown());move(move);
        assertEquals("CROWNED",game.piece(piece).status);assertEquals(76,game.piece(piece).progress);
    }
}
