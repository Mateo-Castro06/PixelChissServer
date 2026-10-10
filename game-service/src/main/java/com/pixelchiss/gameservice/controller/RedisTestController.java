package com.pixelchiss.gameservice.controller;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@ConditionalOnProperty(name="game.diagnostics.enabled",havingValue="true")
public class RedisTestController {

    private final RedisTemplate<String, String> redisTemplate;

    public RedisTestController(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/test/redis")
    @Operation(summary="Optional authenticated Redis connectivity diagnostic",description="Only enabled by game.diagnostics.enabled=true. Writes a test key. Public Gateway: /api/game/test/redis; internal service: /test/redis.",security=@SecurityRequirement(name="BearerJWT"))
    @ApiResponses({@ApiResponse(responseCode="200",description="Connectivity message",content=@Content(mediaType="text/plain",schema=@Schema(type="string"))),
            @ApiResponse(responseCode="401",description="Missing, invalid or expired Bearer JWT",content=@Content),
            @ApiResponse(responseCode="503",description="SERVICE_UNAVAILABLE",content=@Content(schema=@Schema(implementation=GameErrorHandler.Error.class)))})
    public String testRedis() {
        redisTemplate.opsForValue().set("pixelchiss:test", "Redis funciona");

        return redisTemplate.opsForValue().get("pixelchiss:test");
    }
}
