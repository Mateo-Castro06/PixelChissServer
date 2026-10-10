package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.domain.GameState.Effect;
import com.pixelchiss.gameservice.domain.GameState.Player;
import com.pixelchiss.gameservice.domain.GameState.Turn;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import static com.pixelchiss.gameservice.engine.GameException.require;
import static com.pixelchiss.gameservice.engine.RuleEngine.Event.of;

/** Shared authorization, range and consumption rules for human and bot dice. */
public class DiceRules {
    public static final int DICE_COUNT = 2;
    public static final int NORMAL_MAXIMUM = 6;
    public static final String LIMIT_EFFECT = "DICE_MAX_LIMIT";
    public static final String SELECTION_EFFECT = "CHOOSE_DICE";

    private final DiceSource source;

    public DiceRules(DiceSource source) {
        this.source = source;
    }

    public static int maximumValue(Player player) {
        return player.effects.stream()
                .filter(effect -> effect.code.equals(LIMIT_EFFECT) && isActive(effect, player))
                .mapToInt(effect -> effect.maxValue)
                .min().orElse(NORMAL_MAXIMUM);
    }

    public static Optional<Effect> selectionEffect(Player player) {
        return player.effects.stream()
                .filter(effect -> effect.code.equals(SELECTION_EFFECT) && isActive(effect, player))
                .findFirst();
    }

    private static boolean isActive(Effect effect, Player player) {
        return effect.startsAtTurn <= player.turnsStarted;
    }

    public void roll(GameState game, String actor, List<Integer> chosen, List<RuleEngine.Event> events) {
        require(!game.turn.rolled, "ALREADY_ROLLED", "Already rolled in this turn");
        Player player = game.player(actor);
        int maximum = maximumValue(player);
        var selection = selectionEffect(player);
        if (selection.isPresent()) {
            validateSelection(chosen, maximum);
            game.turn.dice = chosen.stream().mapToInt(Integer::intValue).toArray();
            player.effects.remove(selection.get());
            game.turn.diceSelectionRequired = false;
            events.add(of("EFFECT_CONSUMED", "playerId", actor, "effectCode", SELECTION_EFFECT));
        } else {
            require(chosen == null, "UNAUTHORIZED_DICE", "No effect authorizes selected dice");
            game.turn.dice = new int[]{source.roll(maximum), source.roll(maximum)};
        }
        require(Arrays.stream(game.turn.dice).allMatch(value -> value >= 1 && value <= maximum),
                "INVALID_RANDOM_SOURCE", "Dice source outside range");
        game.turn.rolled = true;
        game.turn.doubles = game.turn.dice[0] == game.turn.dice[1];
        events.add(of("DICE_ROLLED", "playerId", actor, "dice", game.turn.dice));
    }

    private void validateSelection(List<Integer> chosen, int maximum) {
        require(chosen != null && chosen.size() == DICE_COUNT,
                "DICE_SELECTION_REQUIRED", "Choose exactly two values");
        require(chosen.stream().allMatch(value -> value != null && value >= 1 && value <= maximum),
                "INVALID_DICE", "Chosen dice exceed authorized range");
    }

    public static int authorizedValue(Turn turn, int index) {
        require(turn.rolled, "ROLL_REQUIRED", "Dice have not been rolled");
        require(index >= 0 && index < DICE_COUNT && !turn.consumed[index],
                "DIE_UNAVAILABLE", "Die unavailable or already consumed");
        return turn.dice[index];
    }

    public static void consume(Turn turn, int index) {
        authorizedValue(turn, index);
        turn.consumed[index] = true;
    }
}
