package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.*;
import java.util.List;

public interface GameCatalog {
    List<CardDefinition> cards(String mapId,String type);
    CardDefinition card(String id);
    List<QuestionDefinition> questions(String mapId);
    QuestionDefinition question(String id);
}
