package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.GameState;
import java.util.Comparator;
import static com.pixelchiss.gameservice.engine.GameException.require;

/** Board seats, not list indices, define the immutable clockwise sequence. */
public final class TurnOrder {
    private TurnOrder() {}

    public static String nextPlayer(GameState game, boolean extraTurn) {
        var current = game.player(game.turn.playerId);
        if (extraTurn && !current.retired) {
            return current.id;
        }
        var remaining = game.players.stream()
                .filter(player -> !player.retired)
                .sorted(Comparator.comparingInt(player -> player.seat))
                .toList();
        require(!remaining.isEmpty(), "INVALID_TURN_ORDER", "No active participant has a seat");
        return remaining.stream()
                .filter(player -> player.seat > current.seat)
                .findFirst()
                .orElse(remaining.getFirst()).id;
    }
}
