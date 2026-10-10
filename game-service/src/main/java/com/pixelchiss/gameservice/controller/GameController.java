package com.pixelchiss.gameservice.controller;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.dto.BoardLayout;
import com.pixelchiss.gameservice.dto.GameView;
import com.pixelchiss.gameservice.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
@SecurityRequirement(name = "BearerJWT")
public class GameController {
    private final GameService games;
    private final Board board;
    public GameController(GameService games, Board board) { this.games = games; this.board = board; }

    @GetMapping("/games/{gameId}")
    @Operation(summary = "Retrieve the complete consistent state of a game",
            description = "Public Gateway path equals service path. Only participants can read it. Does not expose trivia answer keys.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Consistent game snapshot", content = @Content(schema = @Schema(implementation = GameView.class))),
            @ApiResponse(responseCode = "400", description = "INVALID_GAME_ID", content = @Content(schema = @Schema(implementation = GameErrorHandler.Error.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired Bearer JWT", content = @Content),
            @ApiResponse(responseCode = "403", description = "NOT_MEMBER", content = @Content(schema = @Schema(implementation = GameErrorHandler.Error.class))),
            @ApiResponse(responseCode = "404", description = "GAME_NOT_FOUND", content = @Content(schema = @Schema(implementation = GameErrorHandler.Error.class))),
            @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE", content = @Content(schema = @Schema(implementation = GameErrorHandler.Error.class)))
    })
    public GameView state(@Parameter(description = "Game ID, 1–80 letters, digits, underscores or hyphens", required = true)
                          @PathVariable String gameId, @AuthenticationPrincipal Jwt jwt) {
        return games.snapshot(gameId, jwt.getSubject());
    }

    @GetMapping("/board")
    @Operation(summary = "Get layout v1: all 96 cells, safe cells, exits and piece routes",
            description = "Public Gateway path equals service path. Returns the existing map-shaped board definition.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Board layout and routes", content = @Content(schema = @Schema(implementation = BoardLayout.class))),
            @ApiResponse(responseCode = "401", description = "Missing, invalid or expired Bearer JWT", content = @Content)
    })
    public Map<String, Object> board() { return board.definition(); }
}
