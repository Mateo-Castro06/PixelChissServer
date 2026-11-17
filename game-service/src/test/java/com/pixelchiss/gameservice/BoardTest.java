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
                assertEquals(77,route.size());
                assertEquals(76,new HashSet<>(route).size());
                assertEquals(route.get(0),route.get(68));
                assertEquals(2,Collections.frequency(route,seat*17));
                assertEquals(68,new HashSet<>(route.subList(0,69)).size());
                cells.addAll(route);
                assertEquals(seat*17,route.getFirst());
                assertEquals(75+seat*8,route.getLast());
                assertEquals(b.route(seat,0),route);
            }
            var lane = b.route(seat,0).subList(69,77);
            assertEquals(IntStream.range(68+seat*8,76+seat*8).boxed().toList(),lane);
            assertTrue(Collections.disjoint(lanes,lane));
            lanes.addAll(lane);
            assertEquals(4,b.capacity(75+seat*8));
            assertEquals(2,b.capacity(seat*17));
        }
        assertEquals(IntStream.range(0,100).boxed().collect(java.util.stream.Collectors.toSet()),cells);
        assertEquals(32,lanes.size());
        @SuppressWarnings("unchecked") var definitions = (List<Board.Cell>) b.definition().get("cells");
        assertEquals(68,definitions.stream().filter(c -> c.kind().equals("TRACK")).count());
        assertEquals(28,definitions.stream().filter(c -> c.kind().equals("FINISH_LANE")).count());
        assertEquals(4,definitions.stream().filter(c -> c.kind().equals("CROWN")).count());
        assertEquals("v3-100",b.definition().get("layout"));
        assertEquals(100,b.definition().get("cellCount"));
    }
    @Test void confirmedSafeCellsAndSectorRelativeTrivia() {
        Board b = new Board();
        assertEquals(Set.of(0,5,12,17,22,29,34,39,46,51,56,63),Board.SAFE);
        assertEquals(Set.of(8,25,42,59),Board.TRIVIA);
        for (int p=0;p<68;p++) {
            assertEquals(Board.SAFE.contains(p),b.protectedCell(p));
            assertEquals(Board.SAFE.contains(p)?2:1,b.capacity(p));
        }
        assertFalse(b.protectedCell(16));
        for (int p=68;p<100;p++) assertTrue(b.protectedCell(p));
    }
    @Test void everySectorBoundaryWrapAndPrivateEntryFollowClockwiseRoute() {
        Board b = new Board();
        for (int seat=0;seat<4;seat++) {
            for (int p=0;p<68;p++) assertEquals((b.position(seat,0,p)+1)%68,b.position(seat,0,p+1));
            assertEquals((seat*17+67)%68,b.position(seat,0,67));
            assertEquals(seat*17,b.position(seat,0,68));
            assertTrue(Board.SAFE.contains(b.position(seat,0,68)));
            assertEquals(68+seat*8,b.position(seat,0,69));
            assertEquals(75+seat*8,b.position(seat,0,76));
        }
        assertEquals(b.position(0,0,17),b.position(1,0,0));
        assertThrows(IllegalArgumentException.class,()->b.position(0,0,77));
        assertThrows(IllegalArgumentException.class,()->b.position(0,0,-1));
        assertThrows(IllegalArgumentException.class,()->b.position(4,0,0));
        assertThrows(IllegalArgumentException.class,()->b.position(0,4,0));
        var piece = new GameState.Piece("a","p",0);
        assertNull(piece.position); assertEquals(-1,piece.progress); assertEquals("HOME",piece.status);
    }
}
