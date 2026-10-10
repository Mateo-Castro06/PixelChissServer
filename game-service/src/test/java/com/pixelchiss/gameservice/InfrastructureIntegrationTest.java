package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.domain.*;
import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.persistence.*;
import com.pixelchiss.gameservice.service.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in tests use existing Redis/MongoDB, a test Redis prefix, and the existing pixelchiss DB. */
@EnabledIfSystemProperty(named="game.integration",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "REDIS_URL=redis://localhost:6379/0","MONGODB_URI=mongodb://localhost:27017/pixelchiss",
        "EUREKA_URL=http://localhost:8761/eureka/","eureka.client.enabled=false","game.scheduling.enabled=false",
        "game.lobby-token=integration-credential-32-characters"})
@Import(InfrastructureIntegrationTest.FixedDice.class)
@DirtiesContext
class InfrastructureIntegrationTest {
    static final String PREFIX="pixelchiss:game:test:"+UUID.randomUUID();
    static final KeyPair KEYS=keys();
    static KeyPair keys(){try{var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}catch(Exception e){throw new RuntimeException(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        Path path=Path.of("target/integration-public.pem").toAbsolutePath();Files.createDirectories(path.getParent());
        Files.writeString(path,"-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(KEYS.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
        r.add("spring.security.oauth2.resourceserver.jwt.public-key-location",()->"file:"+path);
        r.add("game.redis-prefix",()->PREFIX);
    }
    @TestConfiguration static class FixedDice {@Bean @Primary DiceSource integrationDice(){return max->Math.min(4,max);}}
    @LocalServerPort int port;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired CatalogSeeder seeder;
    @Autowired MongoTemplate mongo;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired RuleEngine rules;
    @Autowired PresenceService presence;
    final List<String> ids=new ArrayList<>();
    CreateGame input(String id){return new CreateGame(id,"test-lobby","classic",List.of(new CreateGame.Participant("test-a",false,0),new CreateGame.Participant("test-b",false,1)),"test-a");}
    String create(){String id="it-"+UUID.randomUUID();ids.add(id);games.create(input(id));return id;}
    @AfterEach void cleanup(){for(String id:ids){redis.delete(PREFIX+":state:"+id);redis.opsForSet().remove(PREFIX+":active",id);
        redis.delete("pixelchiss:game:presence:"+id+":test-a");redis.delete("pixelchiss:game:presence:"+id+":test-b");}}
    @Test void seedsAreIdempotentAndDefinitionsRoundTripFromMongo() throws Exception {
        seeder.run(null);seeder.run(null);var catalog=new MongoGameCatalog(mongo);
        assertEquals(4,mongo.count(Query.query(Criteria.where("_id").in("capture-next","move-safe","dice-limit","choose-dice")),CardDefinition.class));
        assertEquals(8,mongo.count(Query.query(Criteria.where("_id").in(new SeedCatalog().seed.questions().stream().map(QuestionDefinition::id).toList())),QuestionDefinition.class));
        assertEquals(3,catalog.card("dice-limit").parameters().get("turns"));assertEquals(4,catalog.questions("science").size());
    }
    @Test void realRedisRejectsStaleCasAndRecoversCompleteState() {
        String id=create();GameState first=repository.find(id),second=repository.find(id);
        first.version=1;second.version=1;
        assertTrue(repository.commit(first,0,"{}"));assertFalse(repository.commit(second,0,"{}"));
        assertEquals(1,repository.find(id).version);assertEquals(8,repository.find(id).pieces.size());
        assertEquals(1,games.create(input(id)).version());
        assertThrows(GameException.class,()->games.create(new CreateGame(id,"different-lobby","classic",input(id).participants(),"test-a")));
    }
    @Test void realRedisRecoversAppliedDiceEffectsWithoutLosingDuration() {
        String id=create();GameState working=repository.find(id);
        working.player("test-a").effects.add(new GameState.Effect("DICE_MAX_LIMIT",3,2,3));
        working.player("test-a").effects.add(new GameState.Effect("CHOOSE_DICE",1,2,6));
        working.version=1;
        assertTrue(repository.commit(working,0,"{}"));
        var effects=repository.find(id).player("test-a").effects;
        assertEquals(2,effects.size());
        assertEquals(3,effects.getFirst().remainingTurns);
        assertEquals(2,effects.getFirst().startsAtTurn);
        assertEquals(3,effects.getFirst().maxValue);
        assertEquals("CHOOSE_DICE",effects.getLast().code);
        assertEquals(1,effects.getLast().remainingTurns);
    }
    String token(String actor) throws Exception {
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),new JWTClaimsSet.Builder().subject(actor).issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900))).build());
        jwt.sign(new RSASSASigner(KEYS.getPrivate()));return jwt.serialize();
    }
    HttpResponse<String> get(HttpClient client,String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));if(token!=null)request.header("Authorization","Bearer "+token);
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void httpSwaggerJwtAndPrivateLobbyInitializationWork() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            assertEquals(200,get(client,"/swagger-ui/index.html",null).statusCode());
            assertTrue(get(client,"/v3/api-docs",null).body().contains("BearerJWT"));
            assertEquals(401,get(client,"/api/game/board",null).statusCode());
            assertEquals(200,get(client,"/api/game/board",token("test-a")).statusCode());
            String id="it-"+UUID.randomUUID();ids.add(id);
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/games")).header("Content-Type","application/json")
                    .header("X-Lobby-Token","integration-credential-32-characters").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input(id)))).build();
            assertEquals(200,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,get(client,"/api/game/games/"+id,token("test-a")).statusCode());
            assertEquals(403,get(client,"/api/game/games/"+id,token("outsider")).statusCode());
        }
    }
    @Test void defaultOpenApiContainsAllThreeActiveOperationsAndOnlyTwoPublicGamePaths() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var spec=json.readTree(get(client,"/v3/api-docs",null).body());
            assertEquals(Set.of("/api/game/board","/api/game/games/{gameId}","/internal/games"),
                    Set.copyOf(spec.path("paths").propertyNames()));
            var publicSpec=json.readTree(get(client,"/v3/api-docs/public-game",null).body());
            assertEquals(Set.of("/api/game/board","/api/game/games/{gameId}"),
                    Set.copyOf(publicSpec.path("paths").propertyNames()));
            assertFalse(publicSpec.path("paths").has("/test/redis"));
            assertEquals("/",publicSpec.path("servers").get(0).path("url").asString());
        }
    }
    @Test void lobbyContractRequiresOriginalCreatorExplicitSeatsAndServiceCredential() throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            String id="it-"+UUID.randomUUID();ids.add(id);
            var input=new CreateGame(id,"test-lobby","classic",List.of(
                    new CreateGame.Participant("test-a",false,3),new CreateGame.Participant("test-b",false,1)),"test-b");
            var missing=new CreateGame(id,"test-lobby","classic",input.participants(),null,"test-a");
            var missingRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/internal/games"))
                    .header("Content-Type","application/json").header("X-Lobby-Token","integration-credential-32-characters")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(missing))).build();
            var rejection=client.send(missingRequest,HttpResponse.BodyHandlers.ofString());
            assertEquals(400,rejection.statusCode());assertTrue(rejection.body().contains("MISSING_CREATOR"));
            assertNull(repository.find(id));
            var unauthorized=HttpRequest.newBuilder(missingRequest.uri()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
            assertEquals(403,client.send(unauthorized,HttpResponse.BodyHandlers.ofString()).statusCode());
            var authorized=HttpRequest.newBuilder(missingRequest.uri()).header("Content-Type","application/json")
                    .header("X-Lobby-Token","integration-credential-32-characters")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build();
            var response=client.send(authorized,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            var state=repository.find(id);assertEquals("test-b",state.creatorPlayerId);assertEquals("test-b",state.turn.playerId);
            assertEquals(1,state.player("test-b").seat);assertEquals(3,state.player("test-a").seat);
            var specification=json.readTree(get(client,"/v3/api-docs",null).body());
            assertTrue(specification.path("components").path("schemas").path("CreateGame").path("required").toString().contains("creatorPlayerId"));
        }
    }
    static class Inbox implements WebSocket.Listener {
        final BlockingQueue<String> messages=new LinkedBlockingQueue<>();final StringBuilder buffer=new StringBuilder();
        public void onOpen(WebSocket ws){ws.request(1);}
        public CompletionStage<?> onText(WebSocket ws,CharSequence text,boolean last){buffer.append(text);if(last){messages.add(buffer.toString());buffer.setLength(0);}ws.request(1);return null;}
        String await(String type) throws Exception {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<until){String m=messages.poll(100,TimeUnit.MILLISECONDS);if(m!=null&&m.contains("\"type\":\""+type+"\""))return m;}
            throw new AssertionError("Missing WebSocket event "+type);
        }
    }
    void send(WebSocket socket,Object message){socket.sendText(json.writeValueAsString(message),true).join();}
    @Test void rawWebSocketAuthDiceMoveDuplicatesAndRedisPublicationSynchronizeTwoPlayers() throws Exception {
        String id=create();
        try(var client=HttpClient.newHttpClient()) {
            var a=new Inbox();var b=new Inbox();
            var wsA=client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/game"),a).join();
            var wsB=client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/game"),b).join();
            try {
                send(wsA,Map.of("type","AUTH","token",token("test-a")));a.await("AUTHENTICATED");
                send(wsB,Map.of("type","AUTH","token",token("test-b")));b.await("AUTHENTICATED");
                send(wsA,Map.of("type","JOIN_GAME","gameId",id));a.await("GAME_STATE");
                send(wsB,Map.of("type","JOIN_GAME","gameId",id));b.await("GAME_STATE");
                var roll=Map.of("type","ROLL_DICE","gameId",id,"requestId","roll-1","expectedVersion",0);
                send(wsA,roll);a.await("ACTION_ACK");String notification=b.await("GAME_UPDATED");
                assertTrue(notification.contains("DICE_ROLLED"));assertTrue(notification.contains("[4,4]"));
                var m=rules.calculate(games.load(id),"test-a","seat-0-piece-0",0);
                var move=new Action("MOVE_REQUEST",id,"move-1",1L,m.pieceId(),m.dieIndex(),m.dieValue(),m.from(),m.to(),null,null,null,null,null);
                send(wsA,move);a.await("ACTION_ACK");assertTrue(b.await("GAME_UPDATED").contains("PIECE_MOVED"));
                assertEquals(64,games.snapshot(id,"test-b").pieces().getFirst().position);
                send(wsA,move);assertTrue(a.await("ACTION_ACK").contains("\"duplicate\":true"));assertEquals(2,games.load(id).version);
                assertTrue(presence.connected(id,"test-a"));
                send(wsB,Map.of("type","SYNC_REQUEST","gameId",id));assertTrue(b.await("GAME_STATE").contains("\"version\":2"));
            } finally {wsA.sendClose(1000,"done").join();wsB.sendClose(1000,"done").join();}
        }
    }
}
