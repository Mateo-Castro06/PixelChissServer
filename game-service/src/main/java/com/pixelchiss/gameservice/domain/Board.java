package com.pixelchiss.gameservice.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Layout v5: leave the common track five cells before the start, then eight private cells. HOME is a state. */
public class Board {
    public static final String LAYOUT = "v5-100";
    public static final int CELL_COUNT = 100;
    public static final int SEAT_COUNT = 4;
    public static final int PIECES_PER_PLAYER = 4;
    public static final int TRACK_CELL_COUNT = 68;
    public static final int SECTOR_CELL_COUNT = TRACK_CELL_COUNT / SEAT_COUNT;
    public static final int FINISH_CELLS_PER_PLAYER = 8;
    public static final int FINISH_START = TRACK_CELL_COUNT;
    public static final int START_OFFSET = 5;
    // The entry safe is at seat * 17; do not traverse the five cells back to the start.
    public static final int LAST_COMMON_PROGRESS = TRACK_CELL_COUNT - START_OFFSET;
    public static final int FIRST_FINISH_PROGRESS = LAST_COMMON_PROGRESS + 1;
    public static final int FINISH = FIRST_FINISH_PROGRESS + FINISH_CELLS_PER_PLAYER - 1;
    public static final Set<Integer> SAFE = Set.of(0, 5, 12, 17, 22, 29, 34, 39, 46, 51, 56, 63);
    // Preserve one trivia cell per sector, at the original relative offset eight.
    public static final Set<Integer> STARTS = Set.of(5, 22, 39, 56);
    public static final Set<Integer> TRIVIA = Set.of(8, 25, 42, 59);

    public int position(int seat, int pieceNumber, int progress) {
        if (seat < 0 || seat >= SEAT_COUNT || pieceNumber < 0 || pieceNumber >= PIECES_PER_PLAYER
                || progress < 0 || progress > FINISH) {
            throw new IllegalArgumentException("Invalid route coordinate");
        }
        if (progress <= LAST_COMMON_PROGRESS) {
            return (seat * SECTOR_CELL_COUNT + START_OFFSET + progress) % TRACK_CELL_COUNT;
        }
        return FINISH_START + seat * FINISH_CELLS_PER_PLAYER + progress - FIRST_FINISH_PROGRESS;
    }

    public boolean protectedCell(int position) {
        return SAFE.contains(position) || (position >= FINISH_START && position < CELL_COUNT);
    }

    public int capacity(int position) {
        if (isCrown(position)) {
            return PIECES_PER_PLAYER;
        }
        return STARTS.contains(position) ? 4 : SAFE.contains(position) ? 2 : position >= FINISH_START ? 1 : 4;
    }

    private boolean isCrown(int position) {
        return position >= FINISH_START && position < CELL_COUNT
                && (position - FINISH_START) % FINISH_CELLS_PER_PLAYER == FINISH_CELLS_PER_PLAYER - 1;
    }

    public List<Integer> route(int seat, int piece) {
        List<Integer> result = new ArrayList<>();
        for (int progress = 0; progress <= FINISH; progress++) {
            result.add(position(seat, piece, progress));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> definition() {
        var cells = IntStream.range(0, CELL_COUNT).mapToObj(id ->
                new Cell(id, kind(id), SAFE.contains(id), protectedCell(id), capacity(id))).toList();
        var routes = IntStream.range(0, SEAT_COUNT).boxed().collect(Collectors.toMap(
                seat -> seat.toString(), seat -> IntStream.range(0, PIECES_PER_PLAYER)
                        .mapToObj(piece -> route(seat, piece)).toList()));
        return Map.of("layout", LAYOUT, "cellCount", CELL_COUNT, "safe", SAFE,
                "trivia", TRIVIA, "cells", cells, "routes", routes);
    }

    private String kind(int id) {
        if (id < FINISH_START) return "TRACK";
        return isCrown(id) ? "CROWN" : "FINISH_LANE";
    }

    public record Cell(int id, String kind, boolean safe, boolean protectedCell, int capacity) {}
}
