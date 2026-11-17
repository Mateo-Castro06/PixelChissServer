package com.pixelchiss.gameservice.dto;

import com.pixelchiss.gameservice.domain.Board;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** OpenAPI schema for the existing Board.definition response; no wire-format change. */
public record BoardLayout(String layout,
                          @Schema(example = "100") int cellCount,
                          Set<Integer> safe, Set<Integer> trivia,
                          List<Board.Cell> cells,
                          @Schema(description = "Seat 0–3 to four routes indexed by progress: 77 visits, 76 distinct physical IDs; start safe repeats at progress 68, private lane 69–76")
                          Map<String, List<List<Integer>>> routes) {}
