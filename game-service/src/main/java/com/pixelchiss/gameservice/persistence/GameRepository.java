package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.GameState;
import java.util.Set;

public interface GameRepository {
    GameState find(String id);
    boolean create(GameState state, String notification);
    boolean commit(GameState state, long expectedVersion, String notification);
    Set<String> activeIds();
}
