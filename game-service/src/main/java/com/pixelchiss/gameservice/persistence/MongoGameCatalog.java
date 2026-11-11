package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.*;
import java.util.List;
import static com.pixelchiss.gameservice.persistence.CatalogDocuments.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;
import com.pixelchiss.gameservice.engine.GameException;
import java.util.function.Supplier;

@Repository
public class MongoGameCatalog implements GameCatalog {
    private final MongoTemplate mongo;
    private final LegacyChallenges legacy;
    MongoGameCatalog(MongoTemplate mongo, LegacyChallenges legacy) {
        this.mongo = mongo;
        this.legacy = legacy;
    }
    @Override public List<CardDefinition> cards(String map,String type) {
        return read(()->mongo.find(Query.query(new Criteria().andOperator(Criteria.where("type").is(type),
                Criteria.where("_id").nin(legacy.cards.keySet()),
                new Criteria().orOperator(Criteria.where("mapId").is(map),Criteria.where("mapId").is("*")))),CardDocument.class).stream().map(CardDocument::definition).toList());
    }
    @Override public CardDefinition card(String id){return read(()->java.util.Optional.ofNullable(mongo.findById(id,CardDocument.class)).map(CardDocument::definition).orElseGet(() -> legacy.cards.get(id)));}
    @Override public List<QuestionDefinition> questions(String map){return read(()->mongo.find(Query.query(Criteria.where("mapId").is(map).and("_id").nin(legacy.questions.keySet())),QuestionDocument.class).stream().map(QuestionDocument::definition).toList());}
    @Override public QuestionDefinition question(String id){return read(()->java.util.Optional.ofNullable(mongo.findById(id,QuestionDocument.class)).map(QuestionDocument::definition).orElseGet(() -> legacy.questions.get(id)));}
    private <T> T read(Supplier<T> query) {
        try {return query.get();}
        catch(DataAccessException e){throw new GameException("CATALOG_UNAVAILABLE","Game catalog unavailable");}
    }
}
