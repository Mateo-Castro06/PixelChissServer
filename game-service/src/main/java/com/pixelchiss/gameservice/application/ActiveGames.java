package com.pixelchiss.gameservice.application;

import java.util.Set;

/** IDs of games eligible for maintenance and bot scheduling. */
public interface ActiveGames {
    Set<String> activeIds();
}
