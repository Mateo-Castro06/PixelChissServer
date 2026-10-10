package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.bot.BotPolicy;
import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.dto.CreateGame;
import com.pixelchiss.gameservice.engine.GameException;
import com.pixelchiss.gameservice.service.GameService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TurnOrderTest extends EngineFixture {
    private List<CreateGame.Participant> participants(int count) {
        var players = new ArrayList<CreateGame.Participant>();
        for (int seat = 0; seat < count; seat++) {
            players.add(new CreateGame.Participant("player-" + seat, false, seat));
        }
        return players;
    }

    private void initialize(List<CreateGame.Participant> players, String creator) {
        game = engine.create(new CreateGame("g", "l", "classic", players, creator));
    }

    private void expire() {
        clock.now = game.turn.deadline;
        engine.tick(game);
    }

    @Test void creatorStartsFromEverySeatAndEveryListPosition() {
        for (int count = 2; count <= 4; count++) {
            for (int creatorSeat = 0; creatorSeat < count; creatorSeat++) {
                for (int offset = 0; offset < count; offset++) {
                    var players = participants(count);
                    Collections.rotate(players, offset);
                    String creator = "player-" + creatorSeat;
                    initialize(players, creator);
                    assertEquals(creator, game.turn.playerId);
                    assertEquals(creator, game.creatorPlayerId);
                    assertEquals(1, game.player(creator).turnsStarted);
                    assertEquals(1, game.turn.number);
                }
            }
        }
    }

    @Test void clockwiseRotationWorksForTwoThreeAndFourParticipants() {
        for (int count = 2; count <= 4; count++) {
            var players = participants(count);
            Collections.reverse(players);
            initialize(players, "player-" + (count - 1));
            for (int step = 1; step <= count * 2; step++) {
                expire();
                assertEquals("player-" + ((count - 1 + step) % count), game.turn.playerId);
            }
        }
    }

    @Test void sparseSeatsAndReorderedStoredCollectionsStillRotateClockwise() {
        initialize(List.of(new CreateGame.Participant("uuid-z", false, 3),
                new CreateGame.Participant("uuid-a", false, 0),
                new CreateGame.Participant("uuid-m", false, 2)), "uuid-m");
        Collections.reverse(game.players);
        expire(); assertEquals("uuid-z", game.turn.playerId);
        expire(); assertEquals("uuid-a", game.turn.playerId);
        expire(); assertEquals("uuid-m", game.turn.playerId);
        assertEquals("GREEN", game.player("uuid-m").color);
        assertNotNull(game.piece("seat-2-piece-0"));
    }

    @Test void botsUseTheirClockwiseSeatAndTheSameTurnRules() {
        var players = participants(4);
        players.set(3, new CreateGame.Participant("player-3", true, 3));
        initialize(players, "player-2");
        expire(); assertEquals("player-3", game.turn.playerId);
        var bot = new BotPolicy(engine, new SeedCatalog(), () -> 0.0, 0.65);
        engine.act(game, "player-3", bot.next(game));
        engine.act(game, "player-3", action("END_TURN", null, null, null, null, null, null));
        assertEquals("player-0", game.turn.playerId);
    }

    @Test void doublesRepeatCurrentSeatThenContinueClockwise() {
        initialize(participants(4), "player-2");
        engine.act(game, "player-2", action("ROLL_DICE", null, null, null, null, null, null));
        move(engine.calculate(game, "player-2", "seat-2-piece-0", 0));
        move(engine.calculate(game, "player-2", "seat-2-piece-1", 1));
        assertEquals("player-2", game.turn.playerId);
        assertEquals(2, game.player("player-2").turnsStarted);
        expire(); assertEquals("player-3", game.turn.playerId);
    }

    @Test void retiredSeatIsSkippedWithoutMovingTheOtherSeats() {
        initialize(participants(4), "player-2");
        engine.retire(game, "player-3");
        expire(); assertEquals("player-0", game.turn.playerId);
        expire(); assertEquals("player-1", game.turn.playerId);
        expire(); assertEquals("player-2", game.turn.playerId);
        assertEquals(3, game.player("player-3").seat);
        assertEquals(4, game.players.size());
    }

    @Test void retirementOfCurrentCreatorContinuesAtNextClockwiseSeat() {
        initialize(participants(4), "player-2");
        engine.retire(game, "player-2");
        assertEquals("player-3", game.turn.playerId);
        expire(); assertEquals("player-0", game.turn.playerId);
        expire(); assertEquals("player-1", game.turn.playerId);
        expire(); assertEquals("player-3", game.turn.playerId);
        assertEquals("player-2", game.creatorPlayerId);
    }

    @Test void temporaryDisconnectionDoesNotChangeClockwiseSequence() {
        initialize(participants(4), "player-2");
        game.player("player-3").disconnectedAt = clock.millis();
        expire(); assertEquals("player-3", game.turn.playerId);
        expire(); assertEquals("player-0", game.turn.playerId);
    }

    @Test void reconnectPreservesSeatsCreatorAndCurrentTurn() {
        initialize(participants(4), "player-2");
        game.player("player-3").disconnectedAt = clock.millis();
        var repository = new MemoryGames();
        repository.create(game, "{}");
        var service = new GameService(repository, engine, repository.json);
        service.reconnect("g", "player-3");
        game = repository.find("g");
        assertEquals("player-2", game.turn.playerId);
        assertEquals("player-2", game.creatorPlayerId);
        assertEquals(3, game.player("player-3").seat);
        assertNull(game.player("player-3").disconnectedAt);
        expire(); assertEquals("player-3", game.turn.playerId);
    }

    @Test void missingCreatorCannotFallBackToLegacyFirstPlayerOrFirstListEntry() {
        for (String missing : new String[]{null, "", " "}) {
            var error = assertThrows(GameException.class, () -> engine.create(
                    new CreateGame("g", "l", "classic", participants(2), missing, "player-0")));
            assertEquals("MISSING_CREATOR", error.code());
        }
    }

    @Test void creatorMustBeMemberAndLegacyFirstPlayerCannotOverrideCreator() {
        var outsider = assertThrows(GameException.class, () -> engine.create(
                new CreateGame("g", "l", "classic", participants(4), "outsider")));
        assertEquals("INVALID_CREATOR", outsider.code());
        var conflict = assertThrows(GameException.class, () -> engine.create(
                new CreateGame("g", "l", "classic", participants(4), "player-2", "player-0")));
        assertEquals("INVALID_FIRST_PLAYER", conflict.code());
        game = engine.create(new CreateGame("g", "l", "classic", participants(4), "player-2", "player-2"));
        assertEquals("player-2", game.turn.playerId);
    }

    @Test void seatsMustBeExplicitUniqueAndWithinTheExistingBoard() {
        for (Integer invalid : new Integer[]{null, -1, 4, 0}) {
            var players = List.of(new CreateGame.Participant("a", false, 0),
                    new CreateGame.Participant("b", false, invalid));
            var error = assertThrows(GameException.class, () -> initialize(players, "a"));
            assertEquals("INVALID_SEATS", error.code());
        }
    }
}
