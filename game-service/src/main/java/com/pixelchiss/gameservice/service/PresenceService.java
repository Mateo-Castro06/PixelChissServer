package com.pixelchiss.gameservice.service;

import java.time.Clock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Renewable per-connection leases support multiple tabs and multiple Game instances. */
@Service
public class PresenceService {
    private final StringRedisTemplate redis;
    private final Clock clock;
    public PresenceService(StringRedisTemplate redis,Clock clock){this.redis=redis;this.clock=clock;}
    private String key(String game,String player) {return "pixelchiss:game:presence:"+game+":"+player;}
    public void touch(String game,String player,String session) {
        redis.opsForZSet().add(key(game,player),session,clock.millis()+15_000);
        redis.expire(key(game,player),java.time.Duration.ofSeconds(60));
    }
    public void remove(String game,String player,String session) {redis.opsForZSet().remove(key(game,player),session);}
    public boolean connected(String game,String player) {
        Long count=redis.opsForZSet().count(key(game,player),clock.millis()+1,Double.POSITIVE_INFINITY);
        return count!=null && count>0;
    }
}
