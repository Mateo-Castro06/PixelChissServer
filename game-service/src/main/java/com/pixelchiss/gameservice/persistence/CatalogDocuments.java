package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.CardDefinition;
import com.pixelchiss.gameservice.domain.QuestionDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

final class CatalogDocuments {
    private CatalogDocuments() {}

    @Document("game_cards")
    record CardDocument(@Id String id, String name, String description, String type, String effectCode,
                        Map<String, Integer> parameters, List<String> conditions, boolean targetRequired, String mapId) {
        static CardDocument of(CardDefinition card) {
            return new CardDocument(card.id(), card.name(), card.description(), card.type(), card.effectCode(),
                    card.parameters(), card.conditions(), card.targetRequired(), card.mapId());
        }

        CardDefinition definition() {
            return new CardDefinition(id, name, description, type, effectCode, parameters, conditions, targetRequired, mapId);
        }
    }

    @Document("game_questions")
    record QuestionDocument(@Id String id, String mapId, String prompt, List<String> options, int correctIndex) {
        static QuestionDocument of(QuestionDefinition question) {
            return new QuestionDocument(question.id(), question.mapId(), question.prompt(), question.options(), question.correctIndex());
        }

        QuestionDefinition definition() {
            return new QuestionDefinition(id, mapId, prompt, options, correctIndex);
        }
    }
}
