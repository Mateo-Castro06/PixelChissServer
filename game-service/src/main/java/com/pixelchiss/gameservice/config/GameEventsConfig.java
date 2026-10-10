package com.pixelchiss.gameservice.config;

import com.pixelchiss.gameservice.persistence.RedisGameRepository;
import com.pixelchiss.gameservice.websocket.GameWebSocketHandler;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;

@Configuration
public class GameEventsConfig {
    @Bean public RedisMessageListenerContainer gameEvents(RedisConnectionFactory factory,GameWebSocketHandler handler) {
        var container=new RedisMessageListenerContainer(); container.setConnectionFactory(factory);
        container.addMessageListener((message,pattern)->handler.broadcast(message.getBody()),new ChannelTopic(RedisGameRepository.CHANNEL));
        return container;
    }
}
