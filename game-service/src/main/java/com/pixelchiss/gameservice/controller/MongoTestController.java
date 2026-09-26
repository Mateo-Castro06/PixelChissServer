package com.pixelchiss.gameservice.controller;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MongoTestController {

    private final MongoTemplate mongoTemplate;

    public MongoTestController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/test/mongodb")
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