package com.pixelchiss.gameservice.controller;

import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.service.GameService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import static com.pixelchiss.gameservice.engine.GameException.require;

@RestController
public class LobbyIntegrationController {
    private final GameService games;
    private final String secret;
    public LobbyIntegrationController(GameService games,@Value("${game.lobby-token:}") String secret){this.games=games;this.secret=secret;}
    @PostMapping("/internal/games")
    @Operation(summary="Initialize a game authorized by Lobby",description="Private service contract. creatorPlayerId must identify the original room creator among the participants and starts the game. Each participant has a unique clockwise board seat (0–3). Identical retries return current state; different initialization using the same gameId is rejected. Deprecated firstPlayerId, when supplied, must equal creatorPlayerId.",
            security=@SecurityRequirement(name="LobbyToken"))
    @ApiResponses({@ApiResponse(responseCode="200",description="Created or identical retry",content=@Content(schema=@Schema(implementation=GameView.class))),
            @ApiResponse(responseCode="400",description="INVALID_REQUEST, MISSING_CREATOR, INVALID_CREATOR, INVALID_SEATS, INVALID_FIRST_PLAYER or UNKNOWN_MAP",content=@Content(schema=@Schema(implementation=GameErrorHandler.Error.class))),
            @ApiResponse(responseCode="403",description="FORBIDDEN: invalid service token",content=@Content(schema=@Schema(implementation=GameErrorHandler.Error.class))),
            @ApiResponse(responseCode="409",description="GAME_EXISTS: ID already used",content=@Content(schema=@Schema(implementation=GameErrorHandler.Error.class))),
            @ApiResponse(responseCode="503",description="INTEGRATION_DISABLED or SERVICE_UNAVAILABLE",content=@Content(schema=@Schema(implementation=GameErrorHandler.Error.class)))})
    public GameView create(@Parameter(description="Private Lobby service credential, at least 32 characters; never routed publicly",required=true)
                          @RequestHeader(value="X-Lobby-Token",required=false) String supplied,@Valid @RequestBody CreateGame input) {
        require(secret.length()>=32,"INTEGRATION_DISABLED","Configure a Lobby token of at least 32 characters");
        require(supplied!=null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)),
                "FORBIDDEN","Lobby service credential required");
        return games.create(input);
    }
}
