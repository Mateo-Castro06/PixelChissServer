package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.domain.*;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;
import com.pixelchiss.gameservice.engine.GameException;
import java.util.function.Supplier;

@Repository
public class MongoGameCatalog implements GameCatalog {
    private final MongoTemplate mongo;
    public MongoGameCatalog(MongoTemplate mongo){this.mongo=mongo;}
    @Override public List<CardDefinition> cards(String map,String type) {
        return read(()->mongo.find(Query.query(new Criteria().andOperator(Criteria.where("type").is(type),
                new Criteria().orOperator(Criteria.where("mapId").is(map),Criteria.where("mapId").is("*")))),CardDefinition.class));
    }
    @Override public CardDefinition card(String id){return read(()->mongo.findById(id,CardDefinition.class));}
    @Override public List<QuestionDefinition> questions(String map){return read(()->mongo.find(Query.query(Criteria.where("mapId").is(map)),QuestionDefinition.class));}
    @Override public QuestionDefinition question(String id){return read(()->mongo.findById(id,QuestionDefinition.class));}
    private <T> T read(Supplier<T> query) {
        try {return query.get();}
        catch(DataAccessException e){throw new GameException("CATALOG_UNAVAILABLE","Game catalog unavailable");}
    }
}
