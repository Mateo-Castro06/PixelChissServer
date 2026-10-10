package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.domain.GameState.Player;
import com.pixelchiss.gameservice.domain.GameState.Turn;
import java.time.Clock;
import java.util.List;
import static com.pixelchiss.gameservice.engine.RuleEngine.Event.of;

/** Owns turn deadlines, clockwise advancement and per-owner effect duration. */
public class TurnManager {
    private final Clock clock;
    private final long turnMillis;

    public TurnManager(Clock clock, long turnMillis) {
        if (turnMillis <= 0) {
            throw new IllegalArgumentException("Turn duration must be positive");
        }
        this.clock = clock;
        this.turnMillis = turnMillis;
    }

    public void start(GameState game, String actor) {
        long number = game.turn.number;
        Player player = game.player(actor);
        player.turnsStarted++;
        var turn = new Turn();
        turn.number = number;
        turn.playerId = actor;
        turn.deadline = clock.millis() + turnMillis;
        turn.maxDieValue = DiceRules.maximumValue(player);
        turn.diceSelectionRequired = DiceRules.selectionEffect(player).isPresent();
        game.turn = turn;
    }

    public void finish(GameState game, boolean extraTurn, List<RuleEngine.Event> events) {
        expireOwnEffects(game.player(game.turn.playerId), events);
        String next = TurnOrder.nextPlayer(game, extraTurn);
        game.trivia = null;
        game.card = null;
        game.turn.number++;
        start(game, next);
        events.add(of("TURN_CHANGED", "playerId", next, "turnNumber", game.turn.number,
                "deadline", game.turn.deadline));
        if (game.turn.diceSelectionRequired) {
            events.add(of("DICE_SELECTION_REQUIRED", "playerId", next, "maxDieValue", game.turn.maxDieValue));
        }
    }

    private void expireOwnEffects(Player player, List<RuleEngine.Event> events) {
        var effects = player.effects.iterator();
        while (effects.hasNext()) {
            var effect = effects.next();
            if (effect.startsAtTurn > player.turnsStarted) {
                continue;
            }
            effect.remainingTurns--;
            if (effect.remainingTurns <= 0) {
                effects.remove();
                events.add(of("EFFECT_EXPIRED", "playerId", player.id, "effectCode", effect.code));
            } else {
                events.add(of("EFFECT_UPDATED", "playerId", player.id, "effectCode", effect.code,
                        "remainingTurns", effect.remainingTurns));
            }
        }
    }
}
