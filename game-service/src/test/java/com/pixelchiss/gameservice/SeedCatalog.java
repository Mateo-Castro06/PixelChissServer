package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.persistence.*;
import java.util.List;

class SeedCatalog implements GameCatalog {
    final CatalogSeeder.Seed seed;
    SeedCatalog() {
        try(var input=getClass().getResourceAsStream("/seed/catalog.json")) {
            seed=tools.jackson.databind.json.JsonMapper.builder().build().readValue(input,CatalogSeeder.Seed.class);
        } catch(Exception e){throw new RuntimeException(e);}
    }
    public List<CardDefinition> cards(String map,String type){return seed.cards().stream().filter(c->c.type().equals(type)&&(c.mapId().equals(map)||c.mapId().equals("*"))).toList();}
    public CardDefinition card(String id){return seed.cards().stream().filter(c->c.id().equals(id)).findFirst().orElse(null);}
    public List<QuestionDefinition> questions(String map){return seed.questions().stream().filter(q->q.mapId().equals(map)).toList();}
    public QuestionDefinition question(String id){return seed.questions().stream().filter(q->q.id().equals(id)).findFirst().orElse(null);}
}
