package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.*;
import java.util.*;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.ObjectMapper;

/** Stable IDs + $setOnInsert preserve administrator edits and avoid duplicates. */
@Component
@ConditionalOnProperty(name="game.seed.enabled",havingValue="true",matchIfMissing=true)
public class CatalogSeeder implements ApplicationRunner {
    private final MongoTemplate mongo;
    private final ObjectMapper json;
    public CatalogSeeder(MongoTemplate mongo,ObjectMapper json){this.mongo=mongo;this.json=json;}
    public record Seed(List<CardDefinition> cards,List<QuestionDefinition> questions) {}
    @Override public void run(ApplicationArguments args) throws Exception {
        try(var stream=new ClassPathResource("seed/catalog.json").getInputStream()) {
            Seed seed=json.readValue(stream,Seed.class);
            for(var card:seed.cards()) insert(card,card.id(),CardDefinition.class);
            for(var question:seed.questions()) insert(question,question.id(),QuestionDefinition.class);
        }
    }
    private <T> void insert(T value,String id,Class<T> type) {
        Document document=new Document();mongo.getConverter().write(value,document);document.remove("_id");
        Update update=new Update();document.forEach(update::setOnInsert);
        mongo.upsert(Query.query(Criteria.where("_id").is(id)),update,type);
    }
}
