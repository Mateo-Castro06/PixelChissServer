package com.pixelchiss.gameservice;
import com.pixelchiss.gameservice.domain.GameAction;
import com.pixelchiss.gameservice.domain.GameSetup;


import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.engine.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class EngineFixture {
    protected final Board board=new Board();
    protected final MutableClock clock=new MutableClock();
    protected RuleEngine engine=new RuleEngine(board,m->Math.min(4,m),clock,60_000);
    protected GameState game;
    @BeforeEach void create() {
        game=engine.create(new GameSetup("g","l","classic",List.of(new GameSetup.Participant("a",false,0),new GameSetup.Participant("b",false,1),new GameSetup.Participant("c",false,2)),"a"));
    }
    protected GameAction action(String type,String piece,Integer index,Integer value,Integer from,Integer to,List<Integer> choose) {
        return new GameAction(type,"g",UUID.randomUUID().toString(),game.version,piece,index,value,from,to,choose,null,null,null,null);
    }
    // Movement-only scenarios must have no HOME pieces when rolling doubles.
    protected void activateHomeForMovement() {
        game.pieces.stream().filter(p -> p.ownerId.equals("a") && p.status.equals("HOME"))
                .forEach(p -> place(p.id, 0));
    }
    protected void rollForMovement() { activateHomeForMovement(); roll(); }
    protected void roll() {
        engine.act(game,"a",action("ROLL_DICE",null,null,null,null,null,null));
        if ("WAITING".equals(game.turn.animationStatus)) engine.act(game,"a",new GameAction(
                "DICE_ANIMATION_COMPLETED",game.id,UUID.randomUUID().toString(),game.version,
                null,null,null,null,null,null,game.turn.rollId,null,null,null));
    }
    protected void place(String piece,int progress) {
        var p=game.piece(piece); p.status=progress==Board.FINISH?"CROWNED":"BOARD"; p.progress=progress;
        p.position=board.position(game.player(p.ownerId).seat,p.number,progress);
    }
    protected void placeAt(String piece, int position) {
        int seat = game.player(game.piece(piece).ownerId).seat;
        place(piece, Math.floorMod(position - seat * Board.SECTOR_CELL_COUNT - 5, Board.TRACK_CELL_COUNT));
        org.junit.jupiter.api.Assertions.assertEquals(Math.floorMod(position,Board.TRACK_CELL_COUNT),game.piece(piece).position,
                "Fixture must use a common cell reachable by this color before its entry safe");
    }
    protected void move(RuleEngine.Move m) {
        engine.act(game,game.turn.playerId,action("MOVE_REQUEST",m.pieceId(),m.dieIndex(),m.dieValue(),m.from(),m.to(),null));
    }
    static class MutableClock extends Clock {
        long now=1000;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
        public Instant instant(){return Instant.ofEpochMilli(now);}
        public long millis(){return now;}
    }
}
