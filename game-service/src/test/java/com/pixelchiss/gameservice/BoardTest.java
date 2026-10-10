package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BoardTest {
    @Test void routesAccountForExactly96CellsAndFourExitsPerSeat() {
        Board b = new Board();
        Set<Integer> cells = new HashSet<>(), exits = new HashSet<>();
        for (int s=0; s<4; s++) for (int n=0; n<4; n++) {
            cells.addAll(b.route(s,n)); exits.add(b.position(s,n,0));
            assertEquals(69, b.route(s,n).size());
            assertEquals(83+s*4, b.position(s,n,68));
        }
        assertEquals(96,cells.size()); assertEquals(16,exits.size());
        exits.forEach(p->{assertTrue(b.protectedCell(p));assertEquals(1,b.capacity(p));});
        assertEquals(12,Board.SAFE.size());
        Board.SAFE.forEach(p -> { assertTrue(b.protectedCell(p)); assertEquals(2,b.capacity(p)); });
    }
    @Test void physicalPositionDependsOnSeatAndNotOnlyProgress() {
        Board b = new Board();
        assertEquals(b.position(0,0,17), b.position(1,0,1));
        assertNotEquals(b.position(0,0,65), b.position(1,0,65));
        assertNotEquals(b.position(0,0,0), b.position(0,1,0));
        assertThrows(IllegalArgumentException.class, () -> b.position(0,0,69));
        var piece = new GameState.Piece("a","p",0);
        assertNull(piece.position); assertEquals(-1,piece.progress); assertEquals("HOME",piece.status);
    }
}
