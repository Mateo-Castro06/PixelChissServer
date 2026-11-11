package com.pixelchiss.gameservice.application;

import com.pixelchiss.gameservice.engine.RuleEngine;
import java.util.List;

public record GameUpdate(String type, String gameId, long version, String requestId,
                         List<RuleEngine.Event> events, GameSnapshot state) {}
