package com.pixelchiss.gameservice.config;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.application.GameRepository;
import com.pixelchiss.gameservice.application.PayloadFingerprints;
import com.pixelchiss.gameservice.service.GameService;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.domain.GameCatalog;
import com.pixelchiss.gameservice.bot.BotPolicy;
import com.pixelchiss.gameservice.bot.BotPacing;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class GameConfig {
    @Bean public Clock gameClock() {return Clock.systemUTC();}
    @Bean public DiceSource diceSource() {return new SecureDiceSource();}
    @Bean public GameService games(GameRepository repository, RuleEngine rules, PayloadFingerprints fingerprints, Clock clock, BotPacing pacing) {
        return new GameService(repository, rules, fingerprints, clock, pacing);
    }
    @Bean public BotPacing botPacing(
            @Value("${game.bot-pacing.roll-ms:2100}") long roll,
            @Value("${game.bot-pacing.move-ms:1300}") long move,
            @Value("${game.bot-pacing.crown-ms:1300}") long crown,
            @Value("${game.bot-pacing.trivia-ms:1500}") long trivia,
            @Value("${game.bot-pacing.end-turn-ms:500}") long end,
            @Value("${game.bot-pacing.other-ms:800}") long other) {
        return new BotPacing(roll, move, crown, trivia, end, other);
    }
    @Bean public Board board() {return new Board();}
    @Bean public BotPolicy botPolicy(RuleEngine rules,GameCatalog catalog) {
        var random=new java.security.SecureRandom();return new BotPolicy(rules,catalog,random::nextDouble);
    }
    @Bean public RuleEngine rules(Board board,DiceSource dice,Clock clock,GameCatalog catalog,
                                 @Value("${game.turn-millis:30000}") long timeout,
                                 @Value("${game.trivia-millis:15000}") long trivia,
                                 @Value("${game.card-millis:15000}") long card,
                                 @Value("${game.animation-wait-millis:10000}") long animation) {
        return new TriviaRules(board,dice,clock,timeout,catalog,trivia,card).animationWaitMillis(animation);
    }
}
