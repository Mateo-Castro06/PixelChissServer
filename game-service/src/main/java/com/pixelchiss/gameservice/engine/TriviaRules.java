package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.domain.CardDefinition;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.domain.GameState.CardChoice;
import com.pixelchiss.gameservice.domain.GameState.PendingTrivia;
import com.pixelchiss.gameservice.domain.GameState.Piece;
import com.pixelchiss.gameservice.dto.Action;
import com.pixelchiss.gameservice.dto.CreateGame;
import com.pixelchiss.gameservice.persistence.GameCatalog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import static com.pixelchiss.gameservice.engine.GameException.require;

/** Trivia lifecycle and its immediate rewards, using the common movement/turn engine. */
public class TriviaRules extends RuleEngine {
    private final GameCatalog catalog;
    private final long triviaMillis;
    private final CardRules cards;

    public TriviaRules(Board board, DiceSource dice, Clock clock, long turnMillis,
                       GameCatalog catalog, long triviaMillis, long cardMillis) {
        super(board, dice, clock, turnMillis);
        this.catalog = catalog;
        this.triviaMillis = triviaMillis;
        cards = new CardRules(board, clock, cardMillis);
    }

    @Override public GameState create(CreateGame input) {
        require(!catalog.questions(input.mapId()).isEmpty(), "UNKNOWN_MAP", "Map requires a question bank");
        return super.create(input);
    }

    @Override public List<Event> act(GameState game, String actor, Action action) {
        active(game, actor);
        return switch (action.type()) {
            case "TRIVIA_ANSWER" -> answer(game, actor, action);
            case "CARD_TARGET" -> target(game, actor, action);
            default -> super.act(game, actor, action);
        };
    }

    private List<Event> answer(GameState game, String actor, Action action) {
        require(game.trivia != null && game.trivia.playerId.equals(actor)
                        && Objects.equals(game.trivia.id, action.challengeId()),
                "INVALID_CHALLENGE", "Question not active");
        require(clock.millis() < game.trivia.deadline, "CHALLENGE_EXPIRED", "Question deadline expired");
        require(action.answerIndex() != null && action.answerIndex() >= 0
                        && action.answerIndex() < game.trivia.options.size(),
                "INVALID_ANSWER", "Answer index outside options");
        var events = new ArrayList<Event>();
        resolveTrivia(game, action.answerIndex(), events);
        continueTurn(game, events);
        return events;
    }

    private List<Event> target(GameState game, String actor, Action action) {
        require(game.card != null && game.card.playerId.equals(actor)
                        && Objects.equals(game.card.id, action.challengeId()),
                "INVALID_CHALLENGE", "Card not active");
        require(clock.millis() < game.card.deadline, "CHALLENGE_EXPIRED", "Card deadline expired");
        var choice = new CardChoice(action.pieceId(), action.targetPieceId(), action.targetPlayerId(), action.targetPosition());
        var events = new ArrayList<Event>();
        cards.resolveTarget(game, catalog.card(game.card.cardId), actor, choice, events);
        continueTurn(game, events);
        return events;
    }

    @Override protected void afterLanding(GameState game, Piece piece, List<Event> events) {
        if (!piece.status.equals("BOARD") || !Board.TRIVIA.contains(piece.position)) {
            return;
        }
        var questions = catalog.questions(game.mapId);
        require(!questions.isEmpty(), "CATALOG_UNAVAILABLE", "No questions for map");
        var question = questions.get(dice.roll(questions.size()) - 1);
        var pending = new PendingTrivia();
        pending.id = UUID.randomUUID().toString();
        pending.questionId = question.id();
        pending.playerId = piece.ownerId;
        pending.prompt = question.prompt();
        pending.options = question.options();
        pending.deadline = Math.min(game.turn.deadline, clock.millis() + triviaMillis);
        game.trivia = pending;
        events.add(event("TRIVIA_QUESTION", "question", pending));
    }

    private void resolveTrivia(GameState game, Integer answer, List<Event> events) {
        var pending = game.trivia;
        var question = catalog.question(pending.questionId);
        require(question != null, "CATALOG_UNAVAILABLE", "Question definition missing");
        boolean correct = answer != null && answer == question.correctIndex();
        game.trivia = null;
        events.add(event("TRIVIA_RESOLVED", "challengeId", pending.id, "playerId", pending.playerId,
                "correct", correct, "correctIndex", question.correctIndex()));
        var rewards = catalog.cards(game.mapId, correct ? "BUFF" : "DEBUFF");
        if (rewards.isEmpty()) {
            events.add(event("CARD_DISCARDED", "reason", "EMPTY_CATALOG"));
            return;
        }
        grant(game, rewards.get(dice.roll(rewards.size()) - 1), pending.playerId, events);
    }

    public List<CardChoice> choices(GameState game, CardDefinition card, String actor) {
        return cards.choices(game, card, actor);
    }

    /** Invoked by server trivia resolution, never by a client grant action. */
    public void grant(GameState game, CardDefinition card, String actor, List<Event> events) {
        cards.grant(game, card, actor, events);
    }

    @Override public List<Event> tick(GameState game) {
        if (!game.status.equals("ACTIVE")) {
            return List.of();
        }
        var events = new ArrayList<Event>();
        boolean turnExpired = clock.millis() >= game.turn.deadline;
        if (game.trivia != null && clock.millis() >= game.trivia.deadline) {
            resolveExpiredTrivia(game, events);
        }
        if (game.card != null && clock.millis() >= game.card.deadline) {
            events.add(event("CARD_DISCARDED", "cardId", game.card.cardId, "reason", "TIMEOUT"));
            game.card = null;
        }
        if (turnExpired) {
            events.addAll(super.tick(game));
        } else if (!events.isEmpty()) {
            continueTurn(game, events);
        }
        return events;
    }

    private void resolveExpiredTrivia(GameState game, List<Event> events) {
        var pending = game.trivia;
        try {
            resolveTrivia(game, null, events);
        } catch (GameException error) {
            if (!error.code().equals("CATALOG_UNAVAILABLE")) {
                throw error;
            }
            game.trivia = null;
            game.card = null;
            events.clear();
            events.add(event("TRIVIA_RESOLVED", "challengeId", pending.id, "playerId", pending.playerId,
                    "correct", false, "reason", "CATALOG_UNAVAILABLE"));
            events.add(event("CARD_DISCARDED", "reason", "CATALOG_UNAVAILABLE"));
        }
    }
}
