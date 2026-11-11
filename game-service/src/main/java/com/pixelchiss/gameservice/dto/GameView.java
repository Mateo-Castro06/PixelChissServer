package com.pixelchiss.gameservice.dto;
import com.pixelchiss.gameservice.application.GameSnapshot;


import com.pixelchiss.gameservice.domain.GameState;
import java.util.List;

/** Public snapshot deliberately excludes deduplication receipts and question answer keys. */
public record GameView(String gameId, long version, String mapId, String status, String winnerId,
                       List<GameState.Player> players, List<GameState.Piece> pieces,
                       GameState.Turn turn, GameState.PendingTrivia trivia, GameState.PendingCard card) {
    public static GameView of(GameSnapshot g) {
        return new GameView(g.gameId(), g.version(), g.mapId(), g.status(), g.winnerId(),
                g.players(), g.pieces(), g.turn(), g.trivia(), g.card());
    }

    public static GameView of(GameState g) {
        return new GameView(g.id,g.version,g.mapId,g.status,g.winnerId,g.players,g.pieces,g.turn,g.trivia,g.card);
    }
}
