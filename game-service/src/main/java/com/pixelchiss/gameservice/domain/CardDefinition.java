package com.pixelchiss.gameservice.domain;

import java.util.*;

public record CardDefinition(String id,String name,String description,String type,String effectCode,
                             Map<String,Integer> parameters,List<String> conditions,boolean targetRequired,String mapId) {}
