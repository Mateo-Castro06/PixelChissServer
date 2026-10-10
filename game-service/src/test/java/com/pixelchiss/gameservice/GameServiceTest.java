package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.service.GameService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest extends ServiceFixture {
    @Test void duplicateActionIsIdempotentAndReuseOrOldVersionIsRejected() {
        Action a=action("ROLL_DICE",null,null,null,null,null,null);
        var first=service.action("a",a); var duplicate=service.action("a",a);
        assertEquals(1,first.committedVersion()); assertTrue(duplicate.duplicate());
        assertEquals(2,repo.notifications.size());
        assertThrows(GameException.class,()->service.action("a",new Action("END_TURN","g",a.requestId(),0L,null,null,null,null,null,null,null,null,null,null)));
        assertThrows(GameException.class,()->service.action("a",action("END_TURN",null,null,null,null,null,null)));
    }
    @Test void invalidMutationNeverChangesPersistedState() {
        service.action("a",action("ROLL_DICE",null,null,null,null,null,null));
        var before=repo.states.get("g"); game.version=1;
        assertThrows(GameException.class,()->service.action("a",action("MOVE_REQUEST","seat-0-piece-0",0,4,null,20,null)));
        assertEquals(before,repo.states.get("g"));
    }
    @Test void twoConcurrentUpdatesOnlyCommitOne() throws Exception {
        var gate=new CyclicBarrier(2);
        var concurrentRepo=new MemoryGames(){
            @Override public com.pixelchiss.gameservice.domain.GameState find(String id) {
                var state=super.find(id);
                if(Thread.currentThread().getName().startsWith("race")) try{gate.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}
                return state;
            }
            @Override public synchronized boolean commit(com.pixelchiss.gameservice.domain.GameState g,long v,String e) {
                // Avoid the read barrier during CAS itself.
                var current=json.readValue(states.get(g.id),com.pixelchiss.gameservice.domain.GameState.class);
                if(current.version!=v)return false;states.put(g.id,json.writeValueAsString(g));notifications.add(e);return true;
            }
        };
        concurrentRepo.create(game,"{}");
        var app=new GameService(concurrentRepo,engine,concurrentRepo.json);
        try(var pool=Executors.newFixedThreadPool(2,r->new Thread(r,"race-worker"))) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{try{app.action("a",action("ROLL_DICE",null,null,null,null,null,null));return true;}catch(GameException e){assertEquals("CONCURRENT_UPDATE",e.code());return false;}}));
            int successes=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(2,concurrentRepo.notifications.size());
        }
    }
    @Test void snapshotDoesNotExposeReceiptsAndOutsidersCannotRead() {
        service.action("a",action("ROLL_DICE",null,null,null,null,null,null));
        String view=repo.json.writeValueAsString(service.snapshot("g","a"));
        assertFalse(view.contains("receipts")); assertFalse(view.contains("fingerprint"));
        assertThrows(GameException.class,()->service.snapshot("g","outside"));
    }
}
