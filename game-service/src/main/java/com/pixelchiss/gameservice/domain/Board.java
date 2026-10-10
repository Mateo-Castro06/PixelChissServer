package com.pixelchiss.gameservice.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** Layout v1: 64 common cells + 16 individual exits + 16 private finish cells. */
public class Board {
    public static final int CELL_COUNT = 96;
    public static final int SEAT_COUNT = 4;
    public static final int PIECES_PER_PLAYER = 4;
    public static final int TRACK_CELL_COUNT = 64;
    public static final int FINISH_CELLS_PER_PLAYER = 4;
    public static final int EXIT_START = TRACK_CELL_COUNT;
    public static final int FINISH_START = EXIT_START + SEAT_COUNT * PIECES_PER_PLAYER;
    public static final int FINISH = TRACK_CELL_COUNT + FINISH_CELLS_PER_PLAYER;
    public static final Set<Integer> SAFE = Set.of(0, 5, 10, 16, 21, 26, 32, 37, 42, 48, 53, 58);
    public static final Set<Integer> TRIVIA = Set.of(8, 24, 40, 56);

    public int position(int seat, int pieceNumber, int progress) {
        if (seat < 0 || seat >= SEAT_COUNT || pieceNumber < 0 || pieceNumber >= PIECES_PER_PLAYER
                || progress < 0 || progress > FINISH) {
            throw new IllegalArgumentException("Invalid route coordinate");
        }
        if (progress == 0) {
            return EXIT_START + seat * PIECES_PER_PLAYER + pieceNumber;
        }
        if (progress <= TRACK_CELL_COUNT) {
            return (seat * (TRACK_CELL_COUNT / SEAT_COUNT) + progress - 1) % TRACK_CELL_COUNT;
        }
        return FINISH_START + seat * FINISH_CELLS_PER_PLAYER + progress - TRACK_CELL_COUNT - 1;
    }

    public boolean protectedCell(int position) {
        return SAFE.contains(position) || position >= EXIT_START;
    }

    public int capacity(int position) {
        if (isCrown(position)) {
            return PIECES_PER_PLAYER;
        }
        return SAFE.contains(position) ? 2 : 1;
    }

    private boolean isCrown(int position) {
        return position >= FINISH_START && (position - FINISH_START) % FINISH_CELLS_PER_PLAYER == FINISH_CELLS_PER_PLAYER - 1;
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
        return Map.of("layout", "v1", "cellCount", CELL_COUNT, "safe", SAFE,
                "trivia", TRIVIA, "cells", cells, "routes", routes);
    }

    private String kind(int id) {
        if (id < EXIT_START) return "TRACK";
        if (id < FINISH_START) return "EXIT";
        return isCrown(id) ? "CROWN" : "FINISH_LANE";
    }

    public record Cell(int id, String kind, boolean safe, boolean protectedCell, int capacity) {}
}
