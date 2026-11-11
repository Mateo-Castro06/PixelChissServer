package com.pixelchiss.gameservice.domain;

import java.util.*;

public record QuestionDefinition(String id,String mapId,String prompt,List<String> options,int correctIndex) {}
