package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.service.GameScheduler;
import com.pixelchiss.gameservice.service.PresenceService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class GameSchedulerTest extends ServiceFixture {
    private GameScheduler scheduler(PresenceService presence) {
        return new GameScheduler(service, repo, engine, presence, clock, 5000);
    }

    @Test void leasesStartGraceAndRetireOnlyDisconnectedHumans() {
        game.player("b").bot = true;
        repo.states.put("g", repo.json.writeValueAsString(game));
        var maintenance = scheduler(mock(PresenceService.class));
        maintenance.tick();
        var disconnected = repo.find("g");
        assertEquals(clock.millis(), disconnected.player("a").disconnectedAt);
        assertNull(disconnected.player("b").disconnectedAt);
        assertEquals(1, disconnected.version);
        clock.now += 1000; maintenance.tick();
        assertEquals(1, repo.find("g").version);
        clock.now += 4000; maintenance.tick();
        var retired = repo.find("g");
        assertTrue(retired.player("a").retired);
        assertTrue(retired.player("c").retired);
        assertFalse(retired.player("b").retired);
        assertEquals("ABANDONED", retired.status);
    }

    @Test void renewedPresenceClearsGraceWithoutChangingSeatOrTurn() {
        game.player("a").disconnectedAt = clock.millis();
        repo.states.put("g", repo.json.writeValueAsString(game));
        var presence = mock(PresenceService.class);
        when(presence.connected(anyString(), anyString())).thenReturn(true);
        var maintenance = scheduler(presence);
        maintenance.tick();
        var reconnected = repo.find("g");
        assertNull(reconnected.player("a").disconnectedAt);
        assertEquals("a", reconnected.turn.playerId);
        assertEquals(0, reconnected.player("a").seat);
        assertTrue(repo.notifications.getLast().contains("PLAYER_RECONNECTED"));
        maintenance.tick(); assertEquals(1, repo.find("g").version);
    }

    @Test void expiredTurnSkipsRetiredSeatAndKeepsTheOthers() {
        game.player("b").retired = true;
        repo.states.put("g", repo.json.writeValueAsString(game));
        var presence = mock(PresenceService.class);
        when(presence.connected(anyString(), anyString())).thenReturn(true);
        clock.now = game.turn.deadline;
        scheduler(presence).tick();
        var state = repo.find("g");
        assertEquals("c", state.turn.playerId);
        assertEquals(1, state.player("b").seat);
        assertTrue(repo.notifications.getLast().contains("TURN_EXPIRED"));
    }
}
