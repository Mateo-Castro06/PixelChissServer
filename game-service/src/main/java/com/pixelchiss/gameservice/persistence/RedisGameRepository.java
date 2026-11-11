package com.pixelchiss.gameservice.persistence;
import com.pixelchiss.gameservice.application.GameUpdate;

import com.pixelchiss.gameservice.application.GameRepository;
import com.pixelchiss.gameservice.application.ActiveGames;


import com.pixelchiss.gameservice.domain.GameState;
import java.time.Instant;
import io.micrometer.core.instrument.Metrics;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;

/** Single-node Redis scripts atomically commit state, index and publication. */
@Repository
public class RedisGameRepository implements GameRepository, ActiveGames {
    public static final String CHANNEL="pixelchiss:game:events";
    private final String prefix;
    private static final DefaultRedisScript<Long> CREATE=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1])==1 then return 0 end
        local indexType = redis.call('TYPE', KEYS[2]).ok
        if indexType ~= 'set' and indexType ~= 'none' then
          return redis.error_reply('Invalid active games index')
        end
        redis.call('XADD',KEYS[3],'MAXLEN','~',ARGV[6],'*','event',ARGV[5])
        redis.call('HSET',KEYS[1],'version','0','state',ARGV[1])
        redis.call('SADD',KEYS[2],ARGV[2])
        redis.call('PUBLISH',ARGV[3],ARGV[4])
        return 1
        """,Long.class);
    private static final DefaultRedisScript<Long> COMMIT=new DefaultRedisScript<>("""
        if redis.call('HGET',KEYS[1],'version')~=ARGV[1] then return 0 end
        redis.call('HSET',KEYS[1],'version',ARGV[2],'state',ARGV[3])
        if ARGV[4]~='ACTIVE' then redis.call('SREM',KEYS[2],ARGV[5]) end
        redis.call('PUBLISH',ARGV[6],ARGV[7])
        return 1
        """,Long.class);
    private record Initialized(String eventId, String eventType, int schemaVersion, String timestamp,
                               String producer, String correlationId, String gameId, Payload payload) {}
    private record Payload(String lobbyId, String mapId) {}
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    public RedisGameRepository(StringRedisTemplate redis,ObjectMapper json,@Value("${game.redis-prefix:pixelchiss:game}") String prefix) {
        this.redis=redis;this.json=json;this.prefix=prefix;
    }
    private String key(String id) {return prefix+":state:"+id;}
    private String index() {return prefix+":active";}
    @Override public GameState find(String id) {
        Object value=redis.opsForHash().get(key(id),"state");
        return value==null?null:json.readValue(value.toString(),GameState.class);
    }
    @Override public boolean create(GameState g,GameUpdate event) {
        var initialized = new Initialized("game-initialized:" + g.id, "GameInitialized", 1,
                Instant.now().toString(), "game-service", g.id, g.id, new Payload(g.lobbyId, g.mapId));
        boolean created = Long.valueOf(1).equals(redis.execute(CREATE, List.of(key(g.id), index(), prefix + ":integration"),
                json.writeValueAsString(g), g.id, CHANNEL, json.writeValueAsString(event), json.writeValueAsString(initialized), "10000"));
        if (created) Metrics.counter("pixelchiss.game.integration.events", "type", "GameInitialized").increment();
        return created;
    }
    @Override public boolean commit(GameState g,long expected,GameUpdate event) {
        return Long.valueOf(1).equals(redis.execute(COMMIT,List.of(key(g.id),index()),Long.toString(expected),Long.toString(g.version),
                json.writeValueAsString(g),g.status,g.id,CHANNEL,json.writeValueAsString(event)));
    }
    @Override public Set<String> activeIds() {return redis.opsForSet().members(index());}
}
