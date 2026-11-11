package com.pixelchiss.gameservice.application;

import com.pixelchiss.gameservice.domain.GameState;

public interface GameRepository {
    /** A detached snapshot, or null if the game does not exist. */
    GameState find(String id);

    /** Creates a new game and publishes its notification. Returns false if the ID already exists. */
    boolean create(GameState state, GameUpdate notification);

    /** Saves and publishes together only if the game exists and its version matches. */
    boolean commit(GameState state, long expectedVersion, GameUpdate notification);
}
