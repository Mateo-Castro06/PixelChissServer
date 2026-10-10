package com.pixelchiss.gameservice.bot;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.dto.Action;
import com.pixelchiss.gameservice.engine.DiceRules;
import com.pixelchiss.gameservice.engine.RuleEngine;
import com.pixelchiss.gameservice.persistence.GameCatalog;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/** One standard weighted policy; reevaluates legal actions after every committed die. */
public class BotPolicy {
    private static final int CROWN_SCORE = 1000;
    private static final int CAPTURE_SCORE = 400;
    private static final int SAFE_SCORE = 250;
    private static final int RELEASE_SCORE = 140;
    private static final int EXPOSURE_PENALTY = 100;
    private static final int REASONABLE_SCORE_MARGIN = 180;
    private static final int WEIGHT_OFFSET = 205;
    private static final int BASE_ADVANCE_SCORE = 10;

    private final RuleEngine rules;
    private final GameCatalog catalog;
    private final DoubleSupplier random;
    private final double triviaAccuracy;

    public BotPolicy(RuleEngine rules, GameCatalog catalog, DoubleSupplier random, double triviaAccuracy) {
        if (triviaAccuracy < 0 || triviaAccuracy > 1) {
            throw new IllegalArgumentException("Trivia accuracy must be a probability");
        }
        this.rules = rules;
        this.catalog = catalog;
        this.random = random;
        this.triviaAccuracy = triviaAccuracy;
    }

    public Action next(GameState game) {
        var player = game.player(game.turn.playerId);
        if (!game.status.equals("ACTIVE") || !player.bot || player.retired) {
            return null;
        }
        if (game.trivia != null) return answerTrivia(game);
        if (game.card != null) return selectCardTarget(game);
        if (!game.turn.rolled) return roll(game);
        var moves = rules.legalMoves(game, player.id);
        if (moves.isEmpty()) {
            return new Action("END_TURN", game.id, requestId(), game.version,
                    null, null, null, null, null, null, null, null, null, null);
        }
        var selected = weightedMove(game, moves);
        return new Action("MOVE_REQUEST", game.id, requestId(), game.version,
                selected.pieceId(), selected.dieIndex(), selected.dieValue(), selected.from(), selected.to(),
                null, null, null, null, null);
    }

    private Action answerTrivia(GameState game) {
        var question = catalog.question(game.trivia.questionId);
        int answer = question.correctIndex();
        if (random.getAsDouble() >= triviaAccuracy) {
            answer = (answer + 1 + (int) (random.getAsDouble() * (question.options().size() - 1))) % question.options().size();
        }
        return new Action("TRIVIA_ANSWER", game.id, requestId(), game.version,
                null, null, null, null, null, null, game.trivia.id, answer, null, null);
    }

    private Action selectCardTarget(GameState game) {
        var choices = game.card.choices;
        if (choices.isEmpty()) {
            return null;
        }
        int index = Math.min(choices.size() - 1, (int) (random.getAsDouble() * choices.size()));
        var choice = choices.get(index);
        return new Action("CARD_TARGET", game.id, requestId(), game.version,
                choice.pieceId(), null, null, null, choice.targetPosition(), null, game.card.id, null,
                choice.targetPieceId(), choice.targetPlayerId());
    }

    private Action roll(GameState game) {
        var player = game.player(game.turn.playerId);
        int maximum = DiceRules.maximumValue(player);
        List<Integer> chosen = DiceRules.selectionEffect(player).isPresent() ? List.of(maximum, maximum) : null;
        return new Action("ROLL_DICE", game.id, requestId(), game.version,
                null, null, null, null, null, chosen, null, null, null, null);
    }

    private RuleEngine.Move weightedMove(GameState game, List<RuleEngine.Move> moves) {
        var ranked = moves.stream().sorted(Comparator.comparingInt((RuleEngine.Move move) -> score(game, move)).reversed()).toList();
        int best = score(game, ranked.getFirst());
        var reasonable = ranked.stream().filter(move -> score(game, move) >= best - REASONABLE_SCORE_MARGIN).toList();
        double total = reasonable.stream().mapToDouble(move -> weight(game, move, best)).sum();
        double choice = random.getAsDouble() * total;
        for (var move : reasonable) {
            choice -= weight(game, move, best);
            if (choice < 0) {
                return move;
            }
        }
        return reasonable.getLast();
    }

    private int weight(GameState game, RuleEngine.Move move, int best) {
        return Math.max(1, score(game, move) - best + WEIGHT_OFFSET);
    }

    public int score(GameState game, RuleEngine.Move move) {
        int score = move.progress() + BASE_ADVANCE_SCORE;
        if (move.crown()) score += CROWN_SCORE;
        if (move.capture()) score += CAPTURE_SCORE;
        if (move.safe()) score += SAFE_SCORE;
        if (move.release()) score += RELEASE_SCORE;
        if (isExposed(game, move)) score -= EXPOSURE_PENALTY;
        return score;
    }

    private boolean isExposed(GameState game, RuleEngine.Move move) {
        if (move.to() >= Board.TRACK_CELL_COUNT || move.safe()) {
            return false;
        }
        return game.pieces.stream().anyMatch(piece -> {
            if (!piece.status.equals("BOARD") || piece.ownerId.equals(game.turn.playerId)
                    || piece.position == null || piece.position >= Board.TRACK_CELL_COUNT) {
                return false;
            }
            int distance = (move.to() - piece.position + Board.TRACK_CELL_COUNT) % Board.TRACK_CELL_COUNT;
            return distance >= 1 && distance <= DiceRules.NORMAL_MAXIMUM;
        });
    }

    private String requestId() {
        return UUID.randomUUID().toString();
    }
}
