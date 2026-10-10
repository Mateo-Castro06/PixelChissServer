package com.pixelchiss.gameservice.controller;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@ConditionalOnProperty(name="game.diagnostics.enabled",havingValue="true")
public class MongoTestController {

    private final MongoTemplate mongoTemplate;

    public MongoTestController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/test/mongodb")
    @Operation(summary="Optional authenticated Mongo connectivity diagnostic",description="Only enabled by game.diagnostics.enabled=true. Writes a test document. Public Gateway: /api/game/test/mongodb; internal service: /test/mongodb.",security=@SecurityRequirement(name="BearerJWT"))
    @ApiResponses({@ApiResponse(responseCode="200",description="Connectivity message",content=@Content(mediaType="text/plain",schema=@Schema(type="string"))),
            @ApiResponse(responseCode="401",description="Missing, invalid or expired Bearer JWT",content=@Content),
            @ApiResponse(responseCode="503",description="SERVICE_UNAVAILABLE",content=@Content(schema=@Schema(implementation=GameErrorHandler.Error.class)))})
    public String testMongoDB() {
        mongoTemplate.getCollection("test").insertOne(
                new org.bson.Document("message", "MongoDB funciona")
        );

        return mongoTemplate.getCollection("test")
                .find()
                .sort(new org.bson.Document("_id", -1))
                .first()
                .getString("message");
    }
}
