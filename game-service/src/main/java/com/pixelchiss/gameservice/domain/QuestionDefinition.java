package com.pixelchiss.gameservice.domain;

import java.util.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("game_questions")
public record QuestionDefinition(@Id String id,String mapId,String prompt,List<String> options,int correctIndex) {}
