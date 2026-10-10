package com.pixelchiss.gameservice.service;

import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.engine.RuleEngine;
import com.pixelchiss.gameservice.persistence.GameRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "game.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class GameScheduler {
    private static final Logger log = LoggerFactory.getLogger(GameScheduler.class);
    private final GameService games;
    private final GameRepository repository;
    private final RuleEngine rules;
    private final PresenceService presence;
    private final Clock clock;
    private final long disconnectGraceMillis;

    public GameScheduler(GameService games, GameRepository repository, RuleEngine rules,
                         PresenceService presence, Clock clock,
                         @Value("${game.disconnect-grace-millis:60000}") long disconnectGraceMillis) {
        this.games = games;
        this.repository = repository;
        this.rules = rules;
        this.presence = presence;
        this.clock = clock;
        this.disconnectGraceMillis = disconnectGraceMillis;
    }

    @Scheduled(fixedDelayString = "${game.scheduler-millis:1000}")
    public void tick() {
        try {
            for (String id : repository.activeIds()) maintainGame(id);
        } catch (RuntimeException error) {
            log.warn("Game index unavailable ({})", error.getClass().getSimpleName());
        }
    }

    private void maintainGame(String id) {
        try {
            games.system(id, this::maintenanceEvents);
        } catch (RuntimeException error) {
            log.warn("Game maintenance failed ({})", error.getClass().getSimpleName());
        }
    }

    private List<RuleEngine.Event> maintenanceEvents(GameState game) {
        List<RuleEngine.Event> events = new ArrayList<>();
        for (GameState.Player player : game.players) {
            if (player.bot || player.retired || !game.status.equals("ACTIVE")) continue;
            updatePresence(game, player, events);
        }
        events.addAll(rules.tick(game));
        return events;
    }

    private void updatePresence(GameState game, GameState.Player player, List<RuleEngine.Event> events) {
        if (presence.connected(game.id, player.id)) {
            if (player.disconnectedAt != null) {
                player.disconnectedAt = null;
                events.add(RuleEngine.Event.of("PLAYER_RECONNECTED", "playerId", player.id));
            }
            return;
        }
        if (player.disconnectedAt == null) {
            player.disconnectedAt = clock.millis();
            events.add(RuleEngine.Event.of("PLAYER_DISCONNECTED", "playerId", player.id,
                    "retireAt", player.disconnectedAt + disconnectGraceMillis));
            return;
        }
        if (clock.millis() - player.disconnectedAt >= disconnectGraceMillis) {
            events.addAll(rules.retire(game, player.id));
        }
    }
}
