package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.domain.GameState.Piece;
import com.pixelchiss.gameservice.domain.GameState.Player;
import com.pixelchiss.gameservice.domain.GameAction;
import com.pixelchiss.gameservice.domain.GameSetup;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static com.pixelchiss.gameservice.engine.GameException.require;

/** Transport-independent rules; the application commits an isolated aggregate on success. */
public class RuleEngine {
    protected final Board board;
    protected final DiceSource dice;
    protected final Clock clock;
    private final DiceRules diceRules;
    private final TurnManager turns;
    private final long movementMillis;
    private long animationMillis = 10_000;

    public RuleEngine(Board board, DiceSource dice, Clock clock, long turnMillis) {
        this.board = board;
        this.dice = dice;
        this.clock = clock;
        this.movementMillis = turnMillis;
        diceRules = new DiceRules(dice);
        turns = new TurnManager(clock, turnMillis);
    }

    public RuleEngine animationWaitMillis(long millis) {
        require(millis > 0, "INVALID_TIMEOUT", "Animation wait must be positive");
        animationMillis = millis;
        return this;
    }

    public record Event(String type, Map<String, Object> data) {
        public static Event of(String type, Object... pairs) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (int index = 0; index < pairs.length; index += 2) {
                data.put((String) pairs[index], pairs[index + 1]);
            }
            return new Event(type, data);
        }
    }

    public record Move(String pieceId, int dieIndex, int dieValue, Integer from, int to,
                       int progress, boolean capture, boolean safe, boolean crown, boolean release) {}

    protected Event event(String type, Object... pairs) {
        return Event.of(type, pairs);
    }

    public GameState create(GameSetup input) {
        validateInitialization(input);
        var game = new GameState();
        game.boardLayout = Board.LAYOUT;
        game.id = input.gameId();
        game.lobbyId = input.lobbyId();
        game.mapId = input.mapId();
        game.creatorPlayerId = input.creatorPlayerId();
        createPlayersAndPieces(game, input.participants());
        startTurn(game, game.creatorPlayerId);
        return game;
    }

    private void validateInitialization(GameSetup input) {
        require(input != null && input.participants() != null && input.participants().size() >= 2
                        && input.participants().size() <= Board.SEAT_COUNT,
                "INVALID_PARTICIPANTS", "Requires two to four participants");
        require(input.creatorPlayerId() != null && !input.creatorPlayerId().isBlank(),
                "MISSING_CREATOR", "creatorPlayerId identifying the original Lobby room creator is required");
        Set<String> ids = new HashSet<>();
        Set<Integer> seats = new HashSet<>();
        for (var participant : input.participants()) {
            require(participant != null && participant.playerId() != null
                            && !participant.playerId().isBlank() && ids.add(participant.playerId()),
                    "INVALID_PARTICIPANTS", "Unique player IDs required");
            require(participant.seat() != null && participant.seat() >= 0
                            && participant.seat() < Board.SEAT_COUNT && seats.add(participant.seat()),
                    "INVALID_SEATS", "Each participant requires a unique clockwise board seat from 0 to 3");
        }
        require(ids.contains(input.creatorPlayerId()), "INVALID_CREATOR",
                "The original room creator must belong to the game");
        require(input.firstPlayerId() == null || input.firstPlayerId().equals(input.creatorPlayerId()),
                "INVALID_FIRST_PLAYER", "firstPlayerId must equal the original room creator");
    }

    private void createPlayersAndPieces(GameState game, List<GameSetup.Participant> participants) {
        var clockwise = participants.stream()
                .sorted(Comparator.comparingInt(GameSetup.Participant::seat)).toList();
        for (var participant : clockwise) {
            game.players.add(new Player(participant.playerId(), participant.seat(), participant.bot()));
            for (int number = 0; number < Board.PIECES_PER_PLAYER; number++) {
                String id = "seat-" + participant.seat() + "-piece-" + number;
                game.pieces.add(new Piece(id, participant.playerId(), number));
            }
        }
    }

    public Player member(GameState game, String actor) {
        require(actor != null && !actor.isBlank(), "UNAUTHENTICATED", "Authentication required");
        Player player = game.player(actor);
        require(player != null, "NOT_MEMBER", "Not a game participant");
        return player;
    }

    protected Player active(GameState game, String actor) {
        Player player = member(game, actor);
        require(game.status.equals("ACTIVE"), "GAME_FINISHED", "Game is finished");
        require(!player.retired, "PLAYER_RETIRED", "Player has retired");
        require(game.turn.playerId.equals(actor), "NOT_YOUR_TURN", "Not your turn");
        require(movementPaused(game) || clock.millis() < game.turn.deadline, "TURN_EXPIRED", "Turn expired");
        return player;
    }

    public List<Event> act(GameState game, String actor, GameAction action) {
        active(game, actor);
        require(game.trivia == null && game.card == null, "PENDING_CHALLENGE", "Resolve current challenge first");
        var events = new ArrayList<Event>();
        switch (action.type()) {
            case "DICE_ANIMATION_COMPLETED" -> confirmAnimation(game, action, events);
            case "CROWN_REQUEST" -> chooseCrown(game, actor, action, events);
            case "ROLL_DICE" -> roll(game, actor, action, events);
            case "MOVE_REQUEST" -> move(game, actor, action, events);
            case "END_TURN" -> {
                require(game.turn.rolled, "ROLL_REQUIRED", "Roll before ending turn");
                require(!"WAITING".equals(game.turn.animationStatus), "ANIMATION_PENDING", "Confirm animation first");
                finishTurn(game, false, events);
            }
            default -> throw new GameException("UNKNOWN_ACTION", "Unsupported action type");
        }
        return events;
    }

    protected void roll(GameState game, String actor, GameAction action, List<Event> events) {
        diceRules.roll(game, actor, action.chosenDice(), events);
        var turn = game.turn;
        turn.consumed = new boolean[2];
        turn.homeRelease = false;
        turn.releasedPieceIds.clear();
        turn.captureLockedPieceIds.clear();
        turn.extraRollAvailable = false;
        turn.thirdDoubleReward = false;
        turn.rollId = java.util.UUID.randomUUID().toString();
        long publishedAt = clock.millis();
        turn.rollPublishedAt = publishedAt;
        turn.movementStartedAt = null;
        turn.animationDeadline = publishedAt + animationMillis;
        turn.animationStatus = game.player(actor).bot ? "COMPLETED" : "WAITING";
        turn.deadline = game.player(actor).bot ? publishedAt + movementMillis : turn.animationDeadline;

        turn.consecutiveDoubles = turn.doubles ? turn.consecutiveDoubles + 1 : 0;
        boolean onlyHome = game.pieces.stream().filter(p -> p.ownerId.equals(actor))
                .noneMatch(p -> p.status.equals("BOARD"));
        if (onlyHome && !turn.doubles) turn.homeAttempts++;
        turn.crownEligible.clear();
        game.pieces.stream().filter(p -> p.ownerId.equals(actor) && p.status.equals("BOARD"))
                .forEach(p -> turn.crownEligible.add(p.id));
        if (turn.doubles) releaseHome(game, actor, events);
        afterRoll(game, events);
        turn.crownEligible.removeIf(id -> !"BOARD".equals(game.piece(id).status));
        if (turn.consecutiveDoubles == 3) {
            turn.thirdDoubleReward = true;
            Arrays.fill(turn.consumed, true);
            turn.crownChoiceRequired = !turn.crownEligible.isEmpty();
            if (turn.crownChoiceRequired) events.add(event("CROWN_CHOICE_REQUIRED", "playerId", actor));
        }
        events.add(event("DICE_ANIMATION_REQUIRED", "rollId", turn.rollId, "turnNumber", turn.number,
                "animationDeadline", turn.animationDeadline, "required", !game.player(actor).bot));
        if (game.player(actor).bot && hasMovementDice(game)) {
            turn.movementStartedAt = publishedAt;
            rememberCaptures(game);
        }
        continueTurn(game, events);
    }

    protected void afterRoll(GameState game, List<Event> events) {}

    public Move calculate(GameState game, String actor, String pieceId, int dieIndex) {
        int dieValue = DiceRules.authorizedValue(game.turn, dieIndex);
        Piece piece = game.piece(pieceId);
        require(piece != null && piece.ownerId.equals(actor), "NOT_YOUR_PIECE", "Piece does not belong to you");
        require(!piece.status.equals("CROWNED"), "CROWNED_PIECE", "Crowned piece cannot move");
        require(!game.turn.captureLockedPieceIds.contains(piece.id),
                "PIECE_CAPTURE_LOCKED", "Capturing piece cannot move again in this roll");
        require(!game.turn.homeRelease || game.turn.crownEligible.contains(piece.id),
                "PIECE_RELEASED_THIS_ROLL", "Only pieces active before this roll can use the remaining die");
        boolean release = piece.status.equals("HOME");
        int progress;
        if (release) {
            throw new GameException("HOME_RELEASE_AUTOMATIC", "Home release is resolved atomically on doubles");
        } else {
            progress = piece.progress + dieValue;
        }
        require(progress <= Board.FINISH, "OVERSHOOT", "Exact finish required");
        int target = board.position(game.player(actor).seat, piece.number, progress);
        boolean crown = progress == Board.FINISH;
        List<Piece> occupants = occupants(game, target, piece.id);
        if (!crown) {
            validateOccupancy(occupants, actor, target);
        }
        boolean capture = !crown && !board.protectedCell(target) && occupants.stream().anyMatch(p -> !p.ownerId.equals(actor));
        return new Move(piece.id, dieIndex, dieValue, piece.position, target, progress,
                capture, Board.SAFE.contains(target), crown, release);
    }

    private void validateOccupancy(List<Piece> occupants, String actor, int target) {
        if (board.protectedCell(target)) {
            require(occupants.size() < board.capacity(target), "CELL_FULL", "Protected cell capacity exceeded");
        }
    }

    protected List<Piece> occupants(GameState game, int position, String exceptPiece) {
        return game.occupants(position, exceptPiece);
    }

    public List<Move> legalMoves(GameState game, String actor) {
        if (!game.status.equals("ACTIVE") || !game.turn.playerId.equals(actor) || !game.turn.rolled
                || game.trivia != null || game.card != null || "WAITING".equals(game.turn.animationStatus)
                || game.turn.crownChoiceRequired || clock.millis() >= game.turn.deadline) {
            return List.of();
        }
        var moves = new ArrayList<Move>();
        for (Piece piece : game.pieces) {
            if (!piece.ownerId.equals(actor)) {
                continue;
            }
            for (int dieIndex = 0; dieIndex <= DiceRules.DICE_COUNT; dieIndex++) {
                try {
                    moves.add(calculate(game, actor, piece.id, dieIndex));
                } catch (GameException ignored) {
                    // A rejected candidate is not a legal option.
                }
            }
        }
        return moves;
    }

    protected void move(GameState game, String actor, GameAction action, List<Event> events) {
        require(!"WAITING".equals(game.turn.animationStatus), "ANIMATION_PENDING", "Confirm animation first");
        require(!game.turn.crownChoiceRequired, "CROWN_CHOICE_REQUIRED", "Choose active piece first");
        rememberCaptures(game);
        require(action.dieIndex() != null && action.dieValue() != null && action.targetPosition() != null,
                "INVALID_ACTION", "Die index, value and destination required");
        Move movement = calculate(game, actor, action.pieceId(), action.dieIndex());
        require(movement.dieValue() == action.dieValue(), "INVENTED_DIE", "Value does not match authorized die");
        require(Objects.equals(movement.from(), action.fromPosition()), "STALE_ORIGIN", "Origin differs from server state");
        require(movement.to() == action.targetPosition(), "INVALID_DESTINATION", "Destination differs from calculated route");
        Piece piece = game.piece(movement.pieceId());
        captureAtDestination(game, movement, events);
        piece.progress = movement.progress();
        piece.position = movement.to();
        piece.status = movement.crown() ? "CROWNED" : "BOARD";
        DiceRules.consume(game.turn, movement.dieIndex());
        events.add(event("PIECE_MOVED", "pieceId", piece.id, "from", movement.from(), "to", movement.to(),
                "dieIndex", movement.dieIndex(), "status", piece.status));
        if (checkVictory(game, actor, events)) {
            return;
        }
        afterLanding(game, piece, events);
        continueTurn(game, events);
    }

    private void captureAtDestination(GameState game, Move movement, List<Event> events) {
        if (!movement.capture()) {
            return;
        }
        game.turn.captureSatisfied = true;
        game.turn.captureLockedPieceIds.add(movement.pieceId());
        for (Piece victim : occupants(game, movement.to(), movement.pieceId())) {
            if (victim.ownerId.equals(game.turn.playerId)) continue;
            victim.home();
            events.add(event("PIECE_CAPTURED", "pieceId", victim.id, "byPieceId", movement.pieceId()));
        }
    }

    protected void afterLanding(GameState game, Piece piece, List<Event> events) {}

    protected boolean checkVictory(GameState game, String actor, List<Event> events) {
        boolean allCrowned = game.pieces.stream().filter(piece -> piece.ownerId.equals(actor))
                .allMatch(piece -> piece.status.equals("CROWNED"));
        if (!allCrowned) {
            return false;
        }
        game.winnerId = actor;
        game.status = "FINISHED";
        game.trivia = null;
        game.card = null;
        events.add(event("GAME_FINISHED", "winnerId", actor));
        return true;
    }

    protected void continueTurn(GameState game, List<Event> events) {
        if (!game.status.equals("ACTIVE") || !game.turn.rolled || game.trivia != null || game.card != null) {
            return;
        }
        if ("WAITING".equals(game.turn.animationStatus) || game.turn.crownChoiceRequired) return;
        rememberCaptures(game);
        if (legalMoves(game, game.turn.playerId).isEmpty()) {
            boolean retryHome = !game.turn.doubles && game.turn.homeAttempts > 0 && game.turn.homeAttempts < 3
                    && game.pieces.stream().noneMatch(p -> p.ownerId.equals(game.turn.playerId) && p.status.equals("BOARD"));
            Arrays.fill(game.turn.consumed, true);
            if (game.turn.thirdDoubleReward) {
                finishTurn(game, false, events);
            } else if (game.turn.doubles || retryHome) {
                if (game.turn.doubles) turns.repeatRoll(game, events);
                game.turn.rolled = false;
                game.turn.extraRollAvailable = game.turn.doubles;
                game.turn.animationStatus = "NONE";
                game.turn.movementStartedAt = null;
                game.turn.deadline = clock.millis() + 60_000;
                events.add(event("ROLL_AVAILABLE", "playerId", game.turn.playerId, "rollId", game.turn.rollId,
                        "extraRollAvailable", game.turn.extraRollAvailable, "consecutiveDoubles", game.turn.consecutiveDoubles));
            } else finishTurn(game, false, events);
        }
    }

    protected void startTurn(GameState game, String actor) {
        turns.start(game, actor);
    }

    protected void finishTurn(GameState game, boolean extraTurn, List<Event> events) {
        if (game.turn.movementStartedAt != null) rememberCaptures(game);
        resolveBlow(game, events);
        Arrays.fill(game.turn.consumed, true);
        turns.finish(game, extraTurn, events);
    }

    public List<Event> tick(GameState game) {
        if (!game.status.equals("ACTIVE") || movementPaused(game)) {
            return List.of();
        }
        var events = new ArrayList<Event>();
        if (clock.millis() < game.turn.deadline) {
            // Recover an already impossible roll without requiring another client action or a timeout.
            continueTurn(game, events);
            return events;
        }
        if (game.card != null || game.trivia != null) events.add(event("CARD_DISCARDED", "reason", "TURN_TIMEOUT"));
        var discarded = new ArrayList<Integer>();
        for (int i = 0; i < game.turn.dice.length; i++) if (!game.turn.consumed[i]) discarded.add(i);
        if ("WAITING".equals(game.turn.animationStatus)) game.turn.animationStatus = "EXPIRED";
        else if (game.turn.movementStartedAt != null) rememberCaptures(game);
        events.add(event("TURN_EXPIRED", "playerId", game.turn.playerId, "rollId", game.turn.rollId,
                "reason", "EXPIRED".equals(game.turn.animationStatus) ? "ANIMATION_TIMEOUT" : "MOVEMENT_TIMEOUT",
                "discardedDice", discarded));
        finishTurn(game, false, events);
        return events;
    }


    private void confirmAnimation(GameState game, GameAction action, List<Event> events) {
        require(Objects.equals(game.turn.rollId, action.challengeId()) && game.turn.rollId != null,
                "STALE_ROLL", "Confirmation must identify the current roll in challengeId");
        if ("COMPLETED".equals(game.turn.animationStatus) || !game.turn.rolled) return;
        require("WAITING".equals(game.turn.animationStatus), "STALE_ROLL", "No animation pending");
        game.turn.animationStatus = "COMPLETED";
        long startedAt = clock.millis();
        if (!hasMovementDice(game) || legalMoves(game, game.turn.playerId).isEmpty()) {
            game.turn.deadline = startedAt + movementMillis;
            continueTurn(game, events);
            return;
        }
        game.turn.movementStartedAt = startedAt;
        game.turn.deadline = startedAt + movementMillis;
        events.add(event("MOVEMENT_TIMER_STARTED", "rollId", game.turn.rollId,
                "startedAt", game.turn.movementStartedAt, "deadline", game.turn.deadline));
        rememberCaptures(game);
        continueTurn(game, events);
    }

    private void releaseHome(GameState game, String actor, List<Event> events) {
        if (game.pieces.stream().noneMatch(p -> p.ownerId.equals(actor) && p.status.equals("HOME"))) return;
        game.turn.homeRelease = true;
        Arrays.fill(game.turn.consumed, true);
        int limit = game.turn.dice[0] == 1 ? 4 : game.turn.dice[0] == 6 ? 3 : 2;
        int start = board.position(game.player(actor).seat, 0, 0);
        var own = occupants(game, start, null).stream().filter(p -> p.ownerId.equals(actor)).count();
        var home = game.pieces.stream().filter(p -> p.ownerId.equals(actor) && p.status.equals("HOME"))
                .limit(Math.min(limit, Math.max(0, 4 - own))).toList();
        home.forEach(p -> game.turn.releasedPieceIds.add(p.id));
        // Index 0 pays for a single release; index 1 remains indivisible. The
        // third pair goes directly to its existing crown phase after release.
        if (home.size() == 1 && game.turn.consecutiveDoubles < 3) game.turn.consumed[1] = false;
        events.add(event("HOME_RELEASE_RESOLVED", "rollId", game.turn.rollId,
                "pieceIds", home.stream().map(p -> p.id).toList(), "consumed", game.turn.consumed.clone()));
        if (home.isEmpty()) return;
        for (var rival : occupants(game, start, null)) if (!rival.ownerId.equals(actor)) {
            rival.home();
            game.turn.captureSatisfied = true;
            events.add(event("PIECE_CAPTURED", "pieceId", rival.id, "reason", "HOME_RELEASE"));
        }
        for (var piece : home) {
            piece.progress = 0; piece.position = start; piece.status = "BOARD";
            events.add(event("PIECE_RELEASED", "pieceId", piece.id, "from", null, "to", start));
        }
    }

    private void chooseCrown(GameState game, String actor, GameAction action, List<Event> events) {
        require(!"WAITING".equals(game.turn.animationStatus), "ANIMATION_PENDING", "Confirm animation first");
        require(game.turn.crownChoiceRequired, "NO_CROWN_CHOICE", "Three doubles required");
        Piece piece = game.piece(action.pieceId());
        require(piece != null && actor.equals(piece.ownerId) && "BOARD".equals(piece.status) && game.turn.crownEligible.contains(piece.id),
                "INVALID_CROWN_PIECE", "Only own active pieces are eligible");
        piece.progress = Board.FINISH;
        piece.position = board.position(game.player(actor).seat, piece.number, Board.FINISH);
        piece.status = "CROWNED";
        game.turn.crownChoiceRequired = false;
        events.add(event("PIECE_CROWNED", "pieceId", piece.id, "reason", "THREE_DOUBLES"));
        if (!checkVictory(game, actor, events)) finishTurn(game, false, events);
    }

    private boolean hasMovementDice(GameState game) {
        return !game.turn.thirdDoubleReward && (!game.turn.consumed[0] || !game.turn.consumed[1]);
    }

    protected boolean movementPaused(GameState game) {
        return game.trivia != null && game.turn.movementRemainingMillis != null
                && Objects.equals(game.turn.rollId, game.turn.pausedMovementRollId);
    }

    protected void pauseMovement(GameState game, List<Event> events) {
        if (game.turn.movementStartedAt == null || game.turn.movementRemainingMillis != null) return;
        game.turn.movementRemainingMillis = Math.max(0, game.turn.deadline - clock.millis());
        game.turn.pausedMovementRollId = game.turn.rollId;
        events.add(event("MOVEMENT_TIMER_PAUSED", "rollId", game.turn.rollId,
                "remainingMillis", game.turn.movementRemainingMillis));
    }

    protected void resumeMovement(GameState game, List<Event> events, long resumedAt) {
        Long remaining = game.turn.movementRemainingMillis;
        String rollId = game.turn.pausedMovementRollId;
        game.turn.movementRemainingMillis = null;
        game.turn.pausedMovementRollId = null;
        if (remaining == null || !game.status.equals("ACTIVE") || !game.turn.rolled
                || game.turn.movementStartedAt == null || !Objects.equals(rollId, game.turn.rollId)) return;
        game.turn.deadline = resumedAt + remaining;
        if (game.turn.consumed[0] && game.turn.consumed[1]) {
            game.turn.movementStartedAt = null;
            return;
        }
        events.add(event("MOVEMENT_TIMER_RESUMED", "rollId", rollId,
                "remainingMillis", remaining, "deadline", game.turn.deadline));
    }

    private void rememberCaptures(GameState game) {
        if (game.turn.captureSatisfied || "WAITING".equals(game.turn.animationStatus)) return;
        for (Move move : legalMoves(game, game.turn.playerId)) if (move.capture()) {
            game.turn.captureCandidates.add(move.pieceId());
            var witnesses = game.turn.captureVictims.computeIfAbsent(move.pieceId(), id -> new java.util.LinkedHashSet<>());
            occupants(game, move.to(), move.pieceId()).stream().filter(p -> !p.ownerId.equals(game.turn.playerId))
                    .forEach(p -> witnesses.add(p.id + ":" + move.to()));
        }
    }

    private void resolveBlow(GameState game, List<Event> events) {
        if (game.turn.captureSatisfied || game.turn.captureCandidates.isEmpty()) return;
        // A card or another participant may have removed the opportunity. Do not guess a victim.
        var candidates = game.turn.captureCandidates.stream().filter(id -> {
            var piece = game.piece(id);
            return piece != null && piece.status.equals("BOARD") && game.turn.captureVictims.getOrDefault(id, Set.of()).stream().anyMatch(w -> {
                int split = w.lastIndexOf(':');
                var victim = game.piece(w.substring(0, split));
                return victim != null && victim.status.equals("BOARD")
                        && Objects.equals(victim.position, Integer.valueOf(w.substring(split + 1)));
            });
        }).toList();
        if (candidates.size() == 1) {
            var piece = game.piece(candidates.getFirst());
            piece.home();
            events.add(event("PIECE_BLOWN", "pieceId", piece.id, "playerId", piece.ownerId));
        } else if (candidates.size() > 1) events.add(event("BLOW_AMBIGUOUS", "pieceIds", candidates));
    }

    public List<Event> retire(GameState game, String actor) {
        Player player = member(game, actor);
        if (player.retired || !game.status.equals("ACTIVE")) {
            return List.of();
        }
        player.retired = true;
        player.effects.clear();
        game.pieces.stream().filter(piece -> piece.ownerId.equals(actor)).forEach(Piece::home);
        var events = new ArrayList<Event>();
        events.add(event("PLAYER_RETIRED", "playerId", actor));
        long activePlayers = game.players.stream().filter(participant -> !participant.retired).count();
        if (activePlayers < 2) {
            game.status = "ABANDONED";
            game.trivia = null;
            game.card = null;
            events.add(event("GAME_ABANDONED", "reason", "INSUFFICIENT_PLAYERS"));
        } else if (game.turn.playerId.equals(actor)) {
            finishTurn(game, false, events);
        }
        return events;
    }
}
