package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.CardDefinition;
import com.pixelchiss.gameservice.domain.QuestionDefinition;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Allows in-flight challenges to finish after catalog migration, without drawing retired content. */
@Component
class LegacyChallenges {
    final Map<String, CardDefinition> cards;
    final Map<String, QuestionDefinition> questions;

    LegacyChallenges(ObjectMapper json) throws IOException {
        try (var stream = new ClassPathResource("seed/legacy-challenges.json").getInputStream()) {
            var archive = json.readValue(stream, CatalogSeeder.Seed.class);
            cards = archive.cards().stream().collect(Collectors.toUnmodifiableMap(CardDefinition::id, Function.identity()));
            questions = archive.questions().stream().collect(Collectors.toUnmodifiableMap(QuestionDefinition::id, Function.identity()));
        }
    }
}
