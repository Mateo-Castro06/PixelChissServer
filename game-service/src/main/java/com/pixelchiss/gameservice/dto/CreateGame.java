package com.pixelchiss.gameservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CreateGame(@NotBlank @Size(max=80) String gameId,
                         @NotBlank @Size(max=80) String lobbyId,
                         @NotBlank String mapId,
                         @NotNull @Size(min=2, max=4) List<@Valid Participant> participants,
                         @Schema(requiredMode=Schema.RequiredMode.REQUIRED,
                                 description="Original Lobby room creator; must be a participant")
                         @Size(max=80) String creatorPlayerId,
                         @Schema(deprecated=true, description="Optional legacy assertion; must equal creatorPlayerId")
                         String firstPlayerId) {
    public CreateGame(String gameId, String lobbyId, String mapId,
                      List<Participant> participants, String creatorPlayerId) {
        this(gameId, lobbyId, mapId, participants, creatorPlayerId, null);
    }

    public record Participant(@NotBlank @Size(max=80) String playerId, boolean bot,
                              @NotNull @Min(0) @Max(3)
                              @Schema(description="Fixed board seat: 0 red, 1 blue, 2 green, 3 yellow; clockwise 0→1→2→3→0")
                              Integer seat) {}
}
