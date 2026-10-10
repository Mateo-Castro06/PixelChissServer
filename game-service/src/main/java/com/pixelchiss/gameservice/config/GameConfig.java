package com.pixelchiss.gameservice.config;

import com.pixelchiss.gameservice.domain.Board;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.persistence.GameCatalog;
import com.pixelchiss.gameservice.bot.BotPolicy;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class GameConfig {
    @Bean public Clock gameClock() {return Clock.systemUTC();}
    @Bean public Board board() {return new Board();}
    @Bean public BotPolicy botPolicy(RuleEngine rules,GameCatalog catalog,@Value("${game.bot-trivia-accuracy:0.65}") double accuracy) {
        var random=new java.security.SecureRandom();return new BotPolicy(rules,catalog,random::nextDouble,accuracy);
    }
    @Bean public RuleEngine rules(Board board,DiceSource dice,Clock clock,GameCatalog catalog,
                                 @Value("${game.turn-millis:60000}") long timeout,
                                 @Value("${game.trivia-millis:15000}") long trivia,
                                 @Value("${game.card-millis:15000}") long card) {
        return new TriviaRules(board,dice,clock,timeout,catalog,trivia,card);
    }
}
