package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.service.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DisconnectTest extends ServiceFixture {
    @Test void graceReconnectAndRepeatedDisconnectionDoNotBlockTurns() {
        var presence=mock(PresenceService.class);
        when(presence.connected("g","b")).thenReturn(true);when(presence.connected("g","c")).thenReturn(true);
        var scheduler=new GameScheduler(service,repo,engine,presence,clock,20_000);
        scheduler.tick();assertFalse(repo.find("g").player("a").retired);
        clock.now+=10_000;when(presence.connected("g","a")).thenReturn(true);scheduler.tick();
        assertNull(repo.find("g").player("a").disconnectedAt);
        when(presence.connected("g","a")).thenReturn(false);scheduler.tick();clock.now+=20_000;scheduler.tick();
        var state=repo.find("g");assertTrue(state.player("a").retired);assertEquals("b",state.turn.playerId);
        assertTrue(state.pieces.stream().filter(p->p.ownerId.equals("a")).allMatch(p->p.status.equals("HOME")));
        long version=state.version;scheduler.tick();assertEquals(version,repo.find("g").version);
    }
}
