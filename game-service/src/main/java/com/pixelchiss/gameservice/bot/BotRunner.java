package com.pixelchiss.gameservice.bot;

import com.pixelchiss.gameservice.engine.GameException;
import com.pixelchiss.gameservice.application.ActiveGames;
import com.pixelchiss.gameservice.service.GameService;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="game.scheduling.enabled",havingValue="true",matchIfMissing=true)
public class BotRunner {
    private static final Logger log=LoggerFactory.getLogger(BotRunner.class);
    private final ActiveGames activeGames;
    private final GameService games;
    private final BotPolicy policy;
    public BotRunner(ActiveGames activeGames,GameService games,BotPolicy policy){this.activeGames=activeGames;this.games=games;this.policy=policy;}
    @Scheduled(fixedDelayString="${game.bot-step-millis:500}") public void step() {
        try {
            for(String id:activeGames.activeIds())try {
                var g=games.load(id);var action=policy.next(g);
                if(action!=null)games.botAction(g.turn.playerId,action);
            } catch(GameException e) { /* Timer or another instance won CAS; evaluate fresh next tick. */ }
            catch(RuntimeException e){log.warn("Bot execution failed ({})",e.getClass().getSimpleName());}
        } catch(RuntimeException e){log.warn("Bot index unavailable ({})",e.getClass().getSimpleName());}
    }
}
