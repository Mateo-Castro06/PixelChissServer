package com.pixelchiss.gameservice.domain;

import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("game_cards")
public record CardDefinition(@Id String id,String name,String description,String type,String effectCode,
                             Map<String,Integer> parameters,List<String> conditions,boolean targetRequired,String mapId) {}
