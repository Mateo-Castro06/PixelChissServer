package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.domain.GameSetup;
import com.pixelchiss.gameservice.engine.GameException;
import com.pixelchiss.gameservice.engine.TriviaRules;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class FinishPathsTest extends EngineFixture {
    static Stream<Arguments> boards() {
        return Stream.of("classic", "science").flatMap(map -> IntStream.range(0,4).mapToObj(seat -> Arguments.of(map,seat)));
    }

    private void start(String map, int seat, int opponentSeat) {
        engine = new TriviaRules(board, maximum -> 1, clock, 60_000, new SeedCatalog(), 15_000, 15_000);
        game = engine.create(new GameSetup("g","l",map,List.of(
                new GameSetup.Participant("a",false,seat),
                new GameSetup.Participant("b",false,opponentSeat)), "a"));
    }

    @ParameterizedTest @MethodSource("boards")
    void allFinalDistancesAndDieValues(String map, int seat) {
        int opponent = (seat + 2) % 4;
        String piece = "seat-" + seat + "-piece-0";
        String rival = "seat-" + opponent + "-piece-0";
        int firstFinal = 80 + seat * 4;
        start(map,seat,opponent);
        assertEquals((seat * 16 + 63) % 64,board.position(seat,0,64));
        assertEquals(List.of(firstFinal,firstFinal+1,firstFinal+2,firstFinal+3),
                board.route(seat,0).subList(65,69));
        for (int remaining=1; remaining<=4; remaining++) {
            for (int die=1;die<=6;die++) {
                start(map,seat,opponent);
                int progress=Board.FINISH-remaining;
                place(piece,progress);
                place(rival,65); // occupying another private lane cannot affect this lane
                game.turn.rolled=true;
                game.turn.dice=new int[]{die,1};
                if (die>remaining) {
                    assertEquals("OVERSHOOT",assertThrows(GameException.class,() -> engine.calculate(game,"a",piece,0)).code());
                    assertEquals(progress,game.piece(piece).progress);
                    assertFalse(game.turn.consumed[0]);
                } else {
                    var move=engine.calculate(game,"a",piece,0);
                    assertEquals(progress+die,move.progress());
                    assertEquals(firstFinal+progress+die-65,move.to());
                    assertFalse(move.capture());
                    assertEquals(remaining==die,move.crown());
                    move(move);
                    assertEquals(remaining==die ? "CROWNED" : "BOARD",game.piece(piece).status);
                    assertEquals(65,game.piece(rival).progress);
                }
            }
        }
        start(map,seat,opponent);
        place(piece,64);
        place("seat-"+seat+"-piece-1",65);
        game.turn.rolled=true;
        game.turn.dice=new int[]{1,2};
        assertEquals("CELL_FULL",assertThrows(GameException.class,() -> engine.calculate(game,"a",piece,0)).code());
        var valid = engine.calculate(game,"a",piece,1);
        assertEquals("INVALID_DESTINATION",assertThrows(GameException.class,() -> engine.act(game,"a",
                action("MOVE_REQUEST",piece,1,2,valid.from(),board.position(opponent,0,66),null))).code());
    }

    @ParameterizedTest @MethodSource("boards")
    void twoHumansCanFinishAfterTraversingPrivateLane(String map, int seat) {
        start(map,seat,(seat+2)%4);
        String prefix="seat-"+seat+"-piece-";
        for (int n=1;n<4;n++) place(prefix+n,68);
        place(prefix+0,64);
        for (int i=0;i<4;i++) {
            if (i%2==0) roll(); // 1,1; automatic extra turn after the first two moves
            if (i==0) assertEquals("CROWNED_PIECE",assertThrows(GameException.class,
                    () -> engine.calculate(game,"a",prefix+1,0)).code());
            move(engine.calculate(game,"a",prefix+0,i%2));
            assertEquals(65+i,game.piece(prefix+0).progress);
        }
        assertEquals("FINISHED",game.status);
        assertEquals("a",game.winnerId);
        assertEquals(83+seat*4,game.piece(prefix+0).position);
    }
}
