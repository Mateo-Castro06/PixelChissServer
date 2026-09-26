package com.pixelchiss.gameservice.controller;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedisTestController {

    private final RedisTemplate<String, String> redisTemplate;

    public RedisTestController(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/test/redis")
    public String testRedis() {
        redisTemplate.opsForValue().set("pixelchiss:test", "Redis funciona");

        return redisTemplate.opsForValue().get("pixelchiss:test");
    }
}