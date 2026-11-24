package com.pixelchiss.gameservice.bot;

import com.pixelchiss.gameservice.engine.RuleEngine.Event;
import java.util.List;

/** Conservative sequential visual budget, without changing game rules or public state. */
public record BotPacing(long rollMillis, long moveMillis, long crownMillis, long triviaMillis,
                        long endTurnMillis, long otherMillis) {
    public BotPacing {
        if (rollMillis <= 0 || moveMillis <= 0 || crownMillis <= 0 || triviaMillis <= 0
                || endTurnMillis <= 0 || otherMillis <= 0)
            throw new IllegalArgumentException("Bot pacing durations must be positive");
    }
    public static BotPacing defaults() { return new BotPacing(2100, 1300, 1300, 1500, 500, 800); }

    public long delay(String action, List<Event> events) {
        long visuals = 0;
        for (Event event : events) {
            visuals += switch (event.type()) {
                case "PIECE_MOVED", "PIECE_RELEASED", "PIECE_CAPTURED", "PIECE_BLOWN", "CARD_PIECE_RETURNED" -> moveMillis;
                case "PIECE_CROWNED" -> crownMillis;
                default -> 0;
            };
        }
        return switch (action) {
            case "ROLL_DICE" -> rollMillis + visuals;
            case "TRIVIA_ANSWER" -> triviaMillis + visuals;
            case "MOVE_REQUEST" -> Math.max(moveMillis, visuals);
            case "CROWN_REQUEST" -> Math.max(crownMillis, visuals);
            case "END_TURN" -> Math.max(endTurnMillis, visuals);
            default -> Math.max(otherMillis, visuals);
        };
    }
}
