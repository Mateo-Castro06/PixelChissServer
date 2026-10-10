package com.pixelchiss.gameservice.service;

import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.dto.Action;
import com.pixelchiss.gameservice.dto.CreateGame;
import com.pixelchiss.gameservice.dto.GameView;
import com.pixelchiss.gameservice.engine.RuleEngine;
import com.pixelchiss.gameservice.persistence.GameRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import static com.pixelchiss.gameservice.engine.GameException.require;

@Service
public class GameService {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final int RECEIPT_LIMIT = 256;
    private final GameRepository repository;
    private final RuleEngine rules;
    private final ObjectMapper json;

    public GameService(GameRepository repository, RuleEngine rules, ObjectMapper json) {
        this.repository = repository;
        this.rules = rules;
        this.json = json;
    }

    public record Update(String type, String gameId, long version, String requestId,
                         List<RuleEngine.Event> events, GameView state) {}
    public record Result(String type, String requestId, long committedVersion, boolean duplicate, GameView state) {}
    public record Sync(String type, GameView state, List<RuleEngine.Move> legalMoves) {}

    public GameState load(String id) {
        validateGameId(id);
        GameState game = repository.find(id);
        require(game != null, "GAME_NOT_FOUND", "Game not found");
        return game;
    }

    public GameView create(CreateGame input) {
        validateGameId(input.gameId());
        GameState game = rules.create(input);
        game.creationFingerprint = fingerprint(input);
        String started = notification(game, null, List.of(RuleEngine.Event.of("GAME_STARTED")));
        if (repository.create(game, started)) return GameView.of(game);

        GameState existing = load(input.gameId());
        require(Objects.equals(existing.creationFingerprint, game.creationFingerprint),
                "GAME_EXISTS", "Game ID already used for another initialization");
        return GameView.of(existing);
    }

    public GameView snapshot(String id, String actor) {
        GameState game = load(id);
        rules.member(game, actor);
        return GameView.of(game);
    }

    public Sync synchronization(String id, String actor) {
        GameState game = load(id);
        rules.member(game, actor);
        return new Sync("GAME_STATE", GameView.of(game), rules.legalMoves(game, actor));
    }

    public void authorizeConnection(String id, String actor) {
        GameState.Player player = rules.member(load(id), actor);
        require(!player.bot, "BOT_IDENTITY", "Bot identities cannot connect");
        require(!player.retired, "PLAYER_RETIRED", "Player has retired");
    }

    public GameView reconnect(String id, String actor) {
        GameState game = load(id);
        GameState.Player player = rules.member(game, actor);
        require(!player.retired && !player.bot, "PLAYER_RETIRED", "Cannot reconnect this participant");
        if (player.disconnectedAt == null || !game.status.equals("ACTIVE")) return GameView.of(game);

        long expectedVersion = game.version;
        player.disconnectedAt = null;
        game.version++;
        String update = notification(game, null, List.of(RuleEngine.Event.of("PLAYER_RECONNECTED", "playerId", actor)));
        require(repository.commit(game, expectedVersion, update),
                "CONCURRENT_UPDATE", "State changed; retry synchronization");
        return GameView.of(game);
    }

    public List<RuleEngine.Move> moves(String id, String actor) {
        GameState game = load(id);
        rules.member(game, actor);
        return rules.legalMoves(game, actor);
    }

    public Result action(String actor, Action action) {
        validateAction(action);
        GameState game = load(action.gameId());
        GameState.Player player = rules.member(game, actor);
        require(!player.bot, "BOT_IDENTITY", "Human connections cannot control bots");
        return apply(game, actor, action);
    }

    public Result botAction(String actor, Action action) {
        GameState game = load(action.gameId());
        GameState.Player player = game.player(actor);
        require(player != null && player.bot, "NOT_BOT", "Bot execution only");
        return apply(game, actor, action);
    }

    private Result apply(GameState game, String actor, Action action) {
        String receiptKey = actor + ":" + action.requestId();
        String payloadFingerprint = fingerprint(action);
        GameState.Receipt receipt = game.receipts.get(receiptKey);
        if (receipt != null) return duplicateResult(game, action, receipt, payloadFingerprint);
        require(game.version == action.expectedVersion(), "STALE_VERSION", "Resynchronize before acting");

        // The repository returns an isolated aggregate. Mutations become visible only after CAS.
        List<RuleEngine.Event> events = rules.act(game, actor, action);
        long expectedVersion = game.version;
        game.version++;
        rememberReceipt(game, receiptKey, payloadFingerprint);
        require(repository.commit(game, expectedVersion, notification(game, action.requestId(), events)),
                "CONCURRENT_UPDATE", "State changed; resynchronize");
        return new Result("ACTION_ACK", action.requestId(), game.version, false, GameView.of(game));
    }

    private Result duplicateResult(GameState game, Action action, GameState.Receipt receipt, String payloadFingerprint) {
        require(receipt.fingerprint().equals(payloadFingerprint),
                "REQUEST_ID_REUSED", "Request ID reused with a different payload");
        return new Result("ACTION_ACK", action.requestId(), receipt.version(), true, GameView.of(game));
    }

    private void rememberReceipt(GameState game, String key, String payloadFingerprint) {
        game.receipts.put(key, new GameState.Receipt(payloadFingerprint, game.version));
        while (game.receipts.size() > RECEIPT_LIMIT) {
            game.receipts.remove(game.receipts.keySet().iterator().next());
        }
    }

    public boolean system(String id, Function<GameState, List<RuleEngine.Event>> mutation) {
        GameState game = load(id);
        long expectedVersion = game.version;
        List<RuleEngine.Event> events = mutation.apply(game);
        if (events.isEmpty()) return false;
        game.version++;
        return repository.commit(game, expectedVersion, notification(game, null, events));
    }

    private void validateGameId(String id) {
        require(validIdentifier(id), "INVALID_GAME_ID", "Invalid game ID");
    }

    private void validateAction(Action action) {
        require(action != null && action.type() != null && validIdentifier(action.requestId())
                        && action.expectedVersion() != null,
                "INVALID_ACTION", "Type, requestId and expectedVersion required");
    }

    private boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private String fingerprint(Object object) {
        try {
            byte[] payload = json.writeValueAsString(object).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String notification(GameState game, String requestId, List<RuleEngine.Event> events) {
        return json.writeValueAsString(new Update("GAME_UPDATED", game.id, game.version, requestId,
                events, GameView.of(game)));
    }
}
