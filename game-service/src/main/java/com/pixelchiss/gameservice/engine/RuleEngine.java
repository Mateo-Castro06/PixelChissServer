package com.pixelchiss.gameservice.engine;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.domain.GameState.Piece;
import com.pixelchiss.gameservice.domain.GameState.Player;
import com.pixelchiss.gameservice.dto.Action;
import com.pixelchiss.gameservice.dto.CreateGame;
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

    public RuleEngine(Board board, DiceSource dice, Clock clock, long turnMillis) {
        this.board = board;
        this.dice = dice;
        this.clock = clock;
        diceRules = new DiceRules(dice);
        turns = new TurnManager(clock, turnMillis);
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

    public GameState create(CreateGame input) {
        validateInitialization(input);
        var game = new GameState();
        game.id = input.gameId();
        game.lobbyId = input.lobbyId();
        game.mapId = input.mapId();
        game.creatorPlayerId = input.creatorPlayerId();
        createPlayersAndPieces(game, input.participants());
        startTurn(game, game.creatorPlayerId);
        return game;
    }

    private void validateInitialization(CreateGame input) {
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

    private void createPlayersAndPieces(GameState game, List<CreateGame.Participant> participants) {
        var clockwise = participants.stream()
                .sorted(Comparator.comparingInt(CreateGame.Participant::seat)).toList();
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
        require(clock.millis() < game.turn.deadline, "TURN_EXPIRED", "Turn expired");
        return player;
    }

    public List<Event> act(GameState game, String actor, Action action) {
        active(game, actor);
        require(game.trivia == null && game.card == null, "PENDING_CHALLENGE", "Resolve current challenge first");
        var events = new ArrayList<Event>();
        switch (action.type()) {
            case "ROLL_DICE" -> roll(game, actor, action, events);
            case "MOVE_REQUEST" -> move(game, actor, action, events);
            case "END_TURN" -> {
                require(game.turn.rolled, "ROLL_REQUIRED", "Roll before ending turn");
                finishTurn(game, false, events);
            }
            default -> throw new GameException("UNKNOWN_ACTION", "Unsupported action type");
        }
        return events;
    }

    protected void roll(GameState game, String actor, Action action, List<Event> events) {
        diceRules.roll(game, actor, action.chosenDice(), events);
        continueTurn(game, events);
    }

    public Move calculate(GameState game, String actor, String pieceId, int dieIndex) {
        int dieValue = DiceRules.authorizedValue(game.turn, dieIndex);
        Piece piece = game.piece(pieceId);
        require(piece != null && piece.ownerId.equals(actor), "NOT_YOUR_PIECE", "Piece does not belong to you");
        require(!piece.status.equals("CROWNED"), "CROWNED_PIECE", "Crowned piece cannot move");
        boolean release = piece.status.equals("HOME");
        int progress;
        if (release) {
            require(game.turn.doubles, "DOUBLES_REQUIRED", "Doubles required to leave home");
            progress = 0;
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
        boolean capture = !crown && !board.protectedCell(target) && !occupants.isEmpty();
        return new Move(piece.id, dieIndex, dieValue, piece.position, target, progress,
                capture, Board.SAFE.contains(target), crown, release);
    }

    private void validateOccupancy(List<Piece> occupants, String actor, int target) {
        if (board.protectedCell(target)) {
            require(occupants.size() < board.capacity(target), "CELL_FULL", "Protected cell capacity exceeded");
        } else {
            require(occupants.stream().noneMatch(piece -> piece.ownerId.equals(actor)),
                    "CELL_FULL", "Own piece blocks destination");
        }
    }

    protected List<Piece> occupants(GameState game, int position, String exceptPiece) {
        return game.occupants(position, exceptPiece);
    }

    public List<Move> legalMoves(GameState game, String actor) {
        if (!game.status.equals("ACTIVE") || !game.turn.playerId.equals(actor) || !game.turn.rolled
                || game.trivia != null || game.card != null) {
            return List.of();
        }
        var moves = new ArrayList<Move>();
        for (Piece piece : game.pieces) {
            if (!piece.ownerId.equals(actor)) {
                continue;
            }
            for (int dieIndex = 0; dieIndex < DiceRules.DICE_COUNT; dieIndex++) {
                try {
                    moves.add(calculate(game, actor, piece.id, dieIndex));
                } catch (GameException ignored) {
                    // A rejected candidate is not a legal option.
                }
            }
        }
        return moves;
    }

    protected void move(GameState game, String actor, Action action, List<Event> events) {
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
        for (Piece victim : occupants(game, movement.to(), movement.pieceId())) {
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
        if (legalMoves(game, game.turn.playerId).isEmpty()) {
            Arrays.fill(game.turn.consumed, true);
            finishTurn(game, game.turn.doubles, events);
        }
    }

    protected void startTurn(GameState game, String actor) {
        turns.start(game, actor);
    }

    protected void finishTurn(GameState game, boolean extraTurn, List<Event> events) {
        turns.finish(game, extraTurn, events);
    }

    public List<Event> tick(GameState game) {
        if (!game.status.equals("ACTIVE") || clock.millis() < game.turn.deadline) {
            return List.of();
        }
        var events = new ArrayList<Event>();
        events.add(event("TURN_EXPIRED", "playerId", game.turn.playerId));
        finishTurn(game, false, events);
        return events;
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
