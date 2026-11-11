package com.pixelchiss.gameservice.domain;

import java.util.List;

public record GameSetup(String gameId, String lobbyId, String mapId, List<Participant> participants,
                        String creatorPlayerId, String firstPlayerId) {
    public GameSetup(String gameId, String lobbyId, String mapId, List<Participant> participants,
                     String creatorPlayerId) {
        this(gameId, lobbyId, mapId, participants, creatorPlayerId, null);
    }
    public record Participant(String playerId, boolean bot, Integer seat) {}
}
