package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.GameState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GameStateSerializationTest {
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test void creatorAndSparseClockwiseSeatsSurvivePersistence() {
        var game = new GameState();
        game.creatorPlayerId = "creator";
        game.players.add(new GameState.Player("other", 3, true));
        game.players.add(new GameState.Player("creator", 1, false));
        game.turn.playerId = "creator";
        var restored = json.readValue(json.writeValueAsString(game), GameState.class);
        assertEquals("creator", restored.creatorPlayerId);
        assertEquals(1, restored.player("creator").seat);
        assertEquals(3, restored.player("other").seat);
        assertEquals("other", com.pixelchiss.gameservice.engine.TurnOrder.nextPlayer(restored, false));
    }

    @Test void existingAggregatesWithoutCreatorRemainReadableAndKeepTheirTurn() {
        var restored = json.readValue("""
                {"id":"legacy","players":[{"id":"a","seat":0,"bot":false},{"id":"b","seat":1,"bot":false}],
                 "turn":{"playerId":"b","number":7}}
                """, GameState.class);
        assertNull(restored.creatorPlayerId);
        assertEquals("b", restored.turn.playerId);
        assertEquals(7, restored.turn.number);
        assertEquals("a", com.pixelchiss.gameservice.engine.TurnOrder.nextPlayer(restored, false));
    }

    @Test void activeEffectsSurviveAggregateSerialization() {
        var game = new GameState();
        var player = new GameState.Player("42", 0, true);
        player.effects.add(new GameState.Effect("DICE_MAX_LIMIT", 3, 7, 3));
        player.effects.add(new GameState.Effect("CHOOSE_DICE", 1, 8, 6));
        game.players.add(player);
        var restored = json.readValue(json.writeValueAsString(game), GameState.class);
        assertEquals(2, restored.players.getFirst().effects.size());
        var limit = restored.players.getFirst().effects.getFirst();
        assertEquals("DICE_MAX_LIMIT", limit.code);
        assertEquals(3, limit.remainingTurns);
        assertEquals(7, limit.startsAtTurn);
        assertEquals(3, limit.maxValue);
        var choose = restored.players.getFirst().effects.getLast();
        assertEquals("CHOOSE_DICE", choose.code);
        assertEquals(1, choose.remainingTurns);
        assertEquals(8, choose.startsAtTurn);
        assertEquals(6, choose.maxValue);
    }

    @Test void existingPersistedEffectFieldsNeedNoMigration() {
        var effect = json.readValue("""
                {"code":"DICE_MAX_LIMIT","maxValue":3,"remainingTurns":3,"startsAtTurn":7}
                """, GameState.Effect.class);
        assertEquals(3, effect.remainingTurns);
        assertEquals(7, effect.startsAtTurn);
        assertEquals(3, effect.maxValue);
    }
}
