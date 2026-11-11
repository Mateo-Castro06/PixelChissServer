package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.application.GameRepository;
import com.pixelchiss.gameservice.domain.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GameRepositoryContractTest {
    @Test
    void memoryRejectsCommitOfUnknownGameWithoutPublishing() {
        var repository = new MemoryGames();
        verifyMissingCommit(repository, "unknown");
        assertTrue(repository.notifications.isEmpty());
    }

    static void verifyMissingCommit(GameRepository repository, String id) {
        var attempted = new GameState();
        attempted.id = id;
        attempted.version = 1;
        assertNull(repository.find(id));
        assertFalse(repository.commit(attempted, 0, null));
        assertNull(repository.find(id));
    }
}
