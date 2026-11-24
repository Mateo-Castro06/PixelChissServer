package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class BoardTest {
    @Test void exactly100PhysicalCellsWithoutHomesOrSeparateExits() {
        Board b = new Board();
        Set<Integer> cells = new HashSet<>(), lanes = new HashSet<>();
        for (int seat=0; seat<4; seat++) {
            for (int piece=0; piece<4; piece++) {
                var route = b.route(seat,piece);
                assertEquals(72,route.size());
                assertEquals(72,new HashSet<>(route).size());
                assertEquals(seat*17,route.get(63));
                assertEquals(1,Collections.frequency(route,(seat*17+5)));
                assertEquals(64,new HashSet<>(route.subList(0,64)).size());
                for(int skipped=1;skipped<5;skipped++)
                    assertFalse(route.contains((seat*17+skipped)%68),"Own final five-cell stretch must not be revisited");
                cells.addAll(route);
                assertEquals((seat*17+5),route.getFirst());
                assertEquals(75+seat*8,route.getLast());
                assertEquals(b.route(seat,0),route);
            }
            var lane = b.route(seat,0).subList(64,72);
            assertEquals(IntStream.range(68+seat*8,76+seat*8).boxed().toList(),lane);
            assertTrue(Collections.disjoint(lanes,lane));
            lanes.addAll(lane);
            assertEquals(4,b.capacity(75+seat*8));
            assertEquals(4,b.capacity((seat*17+5)));
            assertEquals(2,b.capacity(seat*17));
        }
        assertEquals(IntStream.range(0,100).boxed().collect(java.util.stream.Collectors.toSet()),cells);
        assertEquals(32,lanes.size());
        @SuppressWarnings("unchecked") var definitions = (List<Board.Cell>) b.definition().get("cells");
        assertEquals(68,definitions.stream().filter(c -> c.kind().equals("TRACK")).count());
        assertEquals(28,definitions.stream().filter(c -> c.kind().equals("FINISH_LANE")).count());
        assertEquals(4,definitions.stream().filter(c -> c.kind().equals("CROWN")).count());
        assertEquals("v5-100",b.definition().get("layout"));
        assertEquals(63,Board.LAST_COMMON_PROGRESS);assertEquals(64,Board.FIRST_FINISH_PROGRESS);
        assertEquals(71,Board.FINISH);assertEquals(68,Board.TRACK_CELL_COUNT);
        assertEquals(100,b.definition().get("cellCount"));
    }
    @Test void confirmedSafeCellsAndSectorRelativeTrivia() {
        Board b = new Board();
        assertEquals(Set.of(0,5,12,17,22,29,34,39,46,51,56,63),Board.SAFE);
        assertEquals(Set.of(8,25,42,59),Board.TRIVIA);
        for (int p=0;p<68;p++) {
            assertEquals(Board.SAFE.contains(p),b.protectedCell(p));
            assertEquals(Board.STARTS.contains(p)?4:Board.SAFE.contains(p)?2:4,b.capacity(p));
        }
        assertFalse(b.protectedCell(16));
        for (int p=68;p<100;p++) assertTrue(b.protectedCell(p));
    }
    @Test void everySectorBoundaryWrapAndPrivateEntryFollowClockwiseRoute() {
        Board b = new Board();
        for (int seat=0;seat<4;seat++) {
            for (int p=0;p<63;p++) assertEquals((b.position(seat,0,p)+1)%68,b.position(seat,0,p+1));
            assertEquals(Math.floorMod(seat*17-1,68),b.position(seat,0,62));
            assertEquals(seat*17,b.position(seat,0,63));
            assertTrue(Board.SAFE.contains(b.position(seat,0,63)));
            assertEquals(68+seat*8,b.position(seat,0,64));
            assertEquals(75+seat*8,b.position(seat,0,71));
        }
        assertEquals(b.position(0,0,17),b.position(1,0,0));
        assertThrows(IllegalArgumentException.class,()->b.position(0,0,72));
        assertThrows(IllegalArgumentException.class,()->b.position(0,0,-1));
        assertThrows(IllegalArgumentException.class,()->b.position(4,0,0));
        assertThrows(IllegalArgumentException.class,()->b.position(0,4,0));
        var piece = new GameState.Piece("a","p",0);
        assertNull(piece.position); assertEquals(-1,piece.progress); assertEquals("HOME",piece.status);
    }
}
