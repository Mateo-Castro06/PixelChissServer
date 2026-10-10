package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.domain.CardDefinition;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.domain.GameState.CardChoice;
import com.pixelchiss.gameservice.domain.GameState.Effect;
import com.pixelchiss.gameservice.domain.GameState.PendingCard;
import com.pixelchiss.gameservice.domain.GameState.Piece;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import static com.pixelchiss.gameservice.engine.GameException.require;
import static com.pixelchiss.gameservice.engine.RuleEngine.Event.of;

/** Immediate card targeting/application; no inventory or client-controlled grants. */
public class CardRules {
    private static final int DEFAULT_CAPTURE_DISTANCE = 6;
    private static final int DEFAULT_LIMITED_TURNS = 3;
    private static final int DEFAULT_LIMITED_MAXIMUM = 3;
    private final Board board;
    private final Clock clock;
    private final long cardMillis;

    public CardRules(Board board, Clock clock, long cardMillis) {
        this.board = board;
        this.clock = clock;
        this.cardMillis = cardMillis;
    }

    public List<CardChoice> choices(GameState game, CardDefinition card, String actor) {
        if (card == null) {
            return List.of();
        }
        var choices = new ArrayList<CardChoice>();
        for (Piece piece : game.pieces) {
            if (!piece.ownerId.equals(actor) || !piece.status.equals("BOARD")) {
                continue;
            }
            CardChoice choice = switch (card.effectCode()) {
                case "CAPTURE_NEXT" -> nextCapture(game, piece, card);
                case "MOVE_SAFE" -> nextSafe(game, piece);
                default -> null;
            };
            if (choice != null) {
                choices.add(choice);
            }
        }
        return choices;
    }

    private CardChoice nextCapture(GameState game, Piece own, CardDefinition card) {
        int distance = card.parameters().getOrDefault("maxDistance", DEFAULT_CAPTURE_DISTANCE);
        int lastProgress = Math.min(Board.TRACK_CELL_COUNT, own.progress + distance);
        for (int progress = own.progress + 1; progress <= lastProgress; progress++) {
            int position = board.position(game.player(own.ownerId).seat, own.number, progress);
            if (board.protectedCell(position)) {
                continue;
            }
            var victim = game.occupants(position, own.id).stream()
                    .filter(piece -> !piece.ownerId.equals(own.ownerId)).findFirst();
            if (victim.isPresent()) {
                return new CardChoice(own.id, victim.get().id, null, position);
            }
        }
        return null;
    }

    private CardChoice nextSafe(GameState game, Piece own) {
        for (int progress = own.progress + 1; progress <= Board.TRACK_CELL_COUNT; progress++) {
            int position = board.position(game.player(own.ownerId).seat, own.number, progress);
            if (Board.SAFE.contains(position) && game.occupants(position, own.id).size() < board.capacity(position)) {
                return new CardChoice(own.id, null, null, position);
            }
        }
        return null;
    }

    public void grant(GameState game, CardDefinition card, String actor, List<RuleEngine.Event> events) {
        require(game.card == null, "PENDING_CHALLENGE", "A card is already resolving");
        require(card != null && (card.mapId().equals("*") || card.mapId().equals(game.mapId)),
                "INVALID_CARD", "Card unavailable for map");
        if (!card.targetRequired()) {
            apply(game, card, actor, null, events);
            return;
        }
        var options = choices(game, card, actor);
        if (options.isEmpty() || clock.millis() >= game.turn.deadline) {
            events.add(of("CARD_DISCARDED", "cardId", card.id(), "reason", "NO_LEGAL_TARGET"));
            return;
        }
        var pending = new PendingCard();
        pending.id = java.util.UUID.randomUUID().toString();
        pending.cardId = card.id();
        pending.playerId = actor;
        pending.name = card.name();
        pending.description = card.description();
        pending.effectCode = card.effectCode();
        pending.deadline = Math.min(game.turn.deadline, clock.millis() + cardMillis);
        pending.choices = options;
        game.card = pending;
        events.add(of("CARD_TARGET_REQUIRED", "card", pending));
    }

    /** Revalidate against the current aggregate before applying a proposed target. */
    public void resolveTarget(GameState game, CardDefinition card, String actor,
                              CardChoice choice, List<RuleEngine.Event> events) {
        require(choices(game, card, actor).contains(choice), "INVALID_CARD_TARGET", "Target does not satisfy card rules");
        apply(game, card, actor, choice, events);
        game.card = null;
    }

    private void apply(GameState game, CardDefinition card, String actor,
                       CardChoice choice, List<RuleEngine.Event> events) {
        switch (card.effectCode()) {
            case "CAPTURE_NEXT" -> {
                Piece victim = game.piece(choice.targetPieceId());
                victim.home();
                events.add(of("PIECE_CAPTURED", "pieceId", victim.id,
                        "byPieceId", choice.pieceId(), "cardId", card.id()));
            }
            case "MOVE_SAFE" -> moveToSafe(game, card, choice, events);
            case DiceRules.LIMIT_EFFECT -> applyLimit(game, card, actor);
            case DiceRules.SELECTION_EFFECT -> replaceEffect(game, actor,
                    new Effect(DiceRules.SELECTION_EFFECT, 1, game.player(actor).turnsStarted + 1, DiceRules.NORMAL_MAXIMUM));
            default -> throw new GameException("INVALID_CARD", "Unknown effect code");
        }
        events.add(of("EFFECT_APPLIED", "cardId", card.id(), "playerId", actor, "effectCode", card.effectCode()));
    }

    private void moveToSafe(GameState game, CardDefinition card, CardChoice choice, List<RuleEngine.Event> events) {
        Piece piece = game.piece(choice.pieceId());
        Integer from = piece.position;
        for (int progress = piece.progress + 1; progress <= Board.TRACK_CELL_COUNT; progress++) {
            if (board.position(game.player(piece.ownerId).seat, piece.number, progress) == choice.targetPosition()) {
                piece.progress = progress;
                piece.position = choice.targetPosition();
                break;
            }
        }
        events.add(of("PIECE_MOVED", "pieceId", piece.id, "from", from, "to", piece.position, "cardId", card.id()));
    }

    private void applyLimit(GameState game, CardDefinition card, String actor) {
        int maximum = card.parameters().getOrDefault("maxValue", DEFAULT_LIMITED_MAXIMUM);
        int turns = card.parameters().getOrDefault("turns", DEFAULT_LIMITED_TURNS);
        require(maximum >= 1 && maximum <= DiceRules.NORMAL_MAXIMUM && turns > 0,
                "INVALID_CARD", "Invalid dice effect parameters");
        replaceEffect(game, actor, new Effect(DiceRules.LIMIT_EFFECT, turns, game.player(actor).turnsStarted + 1, maximum));
    }

    private void replaceEffect(GameState game, String actor, Effect effect) {
        var player = game.player(actor);
        player.effects.removeIf(existing -> existing.code.equals(effect.code));
        player.effects.add(effect);
    }
}
