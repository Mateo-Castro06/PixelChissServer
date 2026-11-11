package com.pixelchiss.gameservice;
import com.pixelchiss.gameservice.application.GameUpdate;


import com.pixelchiss.gameservice.domain.GameState;
import com.pixelchiss.gameservice.application.GameRepository;
import com.pixelchiss.gameservice.application.ActiveGames;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Same detached read and CAS semantics as Redis; publications retained for assertions. */
class MemoryGames implements GameRepository, ActiveGames {
    final JsonMapper json=JsonMapper.builder().build();
    final Map<String,String> states=new HashMap<>();
    final List<String> notifications=new ArrayList<>();
    public synchronized GameState find(String id) {String s=states.get(id);return s==null?null:json.readValue(s,GameState.class);}
    public synchronized boolean create(GameState g,GameUpdate event) {
        if(states.containsKey(g.id)) return false; states.put(g.id,json.writeValueAsString(g)); notifications.add(json.writeValueAsString(event)); return true;
    }
    public synchronized boolean commit(GameState g,long expected,GameUpdate event) {
        GameState current = find(g.id);
        if (current == null || current.version != expected) return false;
        states.put(g.id,json.writeValueAsString(g));notifications.add(json.writeValueAsString(event));return true;
    }
    public synchronized Set<String> activeIds() {
        Set<String> result=new HashSet<>();for(String id:states.keySet()) if(find(id).status.equals("ACTIVE"))result.add(id);return result;
    }
}
