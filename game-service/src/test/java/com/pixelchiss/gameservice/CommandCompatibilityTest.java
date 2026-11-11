package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.dto.Action;
import com.pixelchiss.gameservice.dto.CreateGame;
import com.pixelchiss.gameservice.persistence.JsonPayloadFingerprints;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandCompatibilityTest {
    @Test void fingerprintsMatchExistingTransportRecordsIncludingNullsAndParticipantOrder() {
        var fingerprints = new JsonPayloadFingerprints(JsonMapper.builder().build());
        var create = new CreateGame("game", "lobby", "classic", List.of(
                new CreateGame.Participant("42", false, 2), new CreateGame.Participant("bot", true, 0)), "42");
        var move = new Action("MOVE_REQUEST", "game", "id", 1L, "piece", 0, 4, null, 1,
                null, null, null, null, null);
        assertEquals(fingerprints.fingerprint(create), fingerprints.fingerprint(create.command()));
        assertEquals(fingerprints.fingerprint(move), fingerprints.fingerprint(move.command()));
    }
}
