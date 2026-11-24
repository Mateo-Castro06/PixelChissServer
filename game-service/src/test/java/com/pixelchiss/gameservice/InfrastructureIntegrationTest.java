package com.pixelchiss.gameservice;
import com.pixelchiss.gameservice.application.GameRepository;


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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in tests require isolated Redis/MongoDB. A preloaded catalog can be tested with seeding disabled. */
@EnabledIfSystemProperty(named="game.integration",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={

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
        r.add("REDIS_URL", () -> Objects.requireNonNull(System.getenv("GAME_TEST_REDIS_URL"),
                "Set GAME_TEST_REDIS_URL to an isolated Redis"));
        r.add("MONGODB_URI", () -> Objects.requireNonNull(System.getenv("GAME_TEST_MONGO_URI"),
                "Set GAME_TEST_MONGO_URI to an isolated MongoDB"));
    }
    @TestConfiguration static class FixedDice {@Bean @Primary DiceSource integrationDice(){return max->Math.min(4,max);}}
    @LocalServerPort int port;
    @Autowired GameService games;
    @Autowired GameRepository repository;
    @Autowired(required=false) CatalogSeeder seeder;
    @Autowired MongoTemplate mongo;
    @Autowired MongoGameCatalog catalog;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired RuleEngine rules;
    @Autowired PresenceService presence;
    final List<String> ids=new ArrayList<>();
    CreateGame input(String id){return new CreateGame(id,"test-lobby","classic",List.of(new CreateGame.Participant("test-a",false,0),new CreateGame.Participant("test-b",false,1)),"test-a");}
    String create(){String id="it-"+UUID.randomUUID();ids.add(id);games.create(input(id).command());return id;}
    @AfterEach void cleanup(){for(String id:ids){redis.delete(PREFIX+":state:"+id);redis.opsForSet().remove(PREFIX+":active",id);
        for(String actor:List.of("test-a","test-b","human-0","human-1","human-2","human-3"))
            redis.delete("pixelchiss:game:presence:"+id+":"+actor);}}
    @Test void seedsAreIdempotentAndDefinitionsRoundTripFromMongo() throws Exception {
        assumeTrue(seeder != null, "Catalog seeding explicitly disabled for this run");
        seeder.run(null);seeder.run(null);
        assertEquals(6,mongo.getCollection("game_cards").countDocuments());
        assertEquals(32,mongo.getCollection("game_questions").countDocuments());
        for (String map : List.of("classic","science")) {
            assertEquals(16,catalog.questions(map).size());
            assertEquals(3,catalog.cards(map,"BUFF").size());
            assertEquals(3,catalog.cards(map,"DEBUFF").size());
        }
        assertEquals("CAPTURE_CHOSEN_CELL",catalog.card("kill-chosen").effectCode());
        assertNotNull(catalog.card("capture-next"));
        assertNotNull(catalog.question("classic-1"));
        assertNull(mongo.getCollection("game_cards").find(new org.bson.Document("_id","capture-next")).first());
        assertNull(mongo.getCollection("game_questions").find(new org.bson.Document("_id","classic-1")).first());
    }
    // Simulate passage of visual time only in the isolated test namespace; no rule assertion is removed.
    private void advanceBotPause(String id) {
        assertNotNull(redis.opsForHash().get(PREFIX+":state:"+id,"botReadyAt"));
        redis.opsForHash().put(PREFIX+":state:"+id,"botReadyAt","1");
    }
    @Test void realRedisSeparatesPublicationsByConfiguredInterval() throws Exception {
        String id=create();GameState state=repository.find(id);state.version++;
        assertTrue(repository.commitBot(state,0,null,80,0));
        long firstReady=Long.parseLong(redis.opsForHash().get(PREFIX+":state:"+id,"botReadyAt").toString());
        state.version++;
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!repository.botReady(id,0)&&System.nanoTime()<until) Thread.sleep(5);
        assertTrue(repository.commitBot(state,1,null,80,0));
        long secondReady=Long.parseLong(redis.opsForHash().get(PREFIX+":state:"+id,"botReadyAt").toString());
        assertTrue(secondReady-firstReady>=80,"Redis published too soon");
        System.out.println("BOT_PACING_OBSERVED configured=80ms separation="+(secondReady-firstReady)+"ms");
    }

    @Test void botPauseAndReceiptSurviveRecoveryInAnotherGameProcess() throws Exception {
        String id=create();
        games.system(id,g->{g.player("test-a").bot=true;return List.of(RuleEngine.Event.of("TEST_FIXTURE"));});
        var paced=new GameService(repository,rules,new JsonPayloadFingerprints(json),Clock.systemUTC(),
                new com.pixelchiss.gameservice.bot.BotPacing(60000,1300,1300,1500,500,800));
        var policy=new com.pixelchiss.gameservice.bot.BotPolicy(rules,catalog,()->0.0);
        var roll=policy.next(paced.load(id));paced.botAction("test-a",roll);
        Object ready=redis.opsForHash().get(PREFIX+":state:"+id,"botReadyAt");
        long version=games.load(id).version;
        int childPort;try(var socket=new ServerSocket(0)) { childPort=socket.getLocalPort(); }
        Process child=null;
        try(var client=HttpClient.newHttpClient()) {
            child=startRecoveryProcess(childPort,client);
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+childPort+"/api/game/games/"+id))
                    .header("Authorization","Bearer "+token("test-b")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals(version,json.readTree(response.body()).path("version").asLong());
            assertFalse(response.body().contains("botReadyAt"));assertFalse(repository.botReady(id,0));
            assertTrue(paced.botAction("test-a",roll).duplicate());
            assertEquals(version,games.load(id).version);
            assertEquals(ready,redis.opsForHash().get(PREFIX+":state:"+id,"botReadyAt"));
        } finally { if(child!=null)stopRecoveryProcess(child); }
    }

    @Test void botPacingIsAtomicDurableAndOutsidePublicState() {
        String id=create();
        GameState first=repository.find(id), stale=repository.find(id);
        first.version++;stale.version++;
        assertTrue(repository.commitBot(first,0,null,60000,0));
        assertFalse(repository.botReady(id,Long.MAX_VALUE)); // Redis owns the time, not the caller JVM
        assertFalse(repository.commitBot(stale,0,null,60000,Long.MAX_VALUE));
        var recovered=new RedisGameRepository(redis,json,PREFIX);
        assertFalse(recovered.botReady(id,0));
        GameState current=recovered.find(id);current.version++;
        assertFalse(recovered.commitBot(current,1,null,60000,0)); // fresh CAS still cannot bypass pause
        assertEquals(1,recovered.find(id).version);
        assertFalse(json.writeValueAsString(games.synchronization(id,"test-a")).contains("botReadyAt"));
        redis.opsForHash().put(PREFIX+":state:"+id,"botReadyAt","1");
        assertTrue(recovered.commitBot(current,1,null,60000,0));
        current=recovered.find(id);current.status="FINISHED";current.version++;
        assertTrue(recovered.commit(current,2,null));
        assertFalse(redis.opsForHash().hasKey(PREFIX+":state:"+id,"botReadyAt"));
        redis.delete(PREFIX+":state:"+id);
        current.version++;
        assertFalse(recovered.commitBot(current,3,null,60000,0));
        assertFalse(redis.hasKey(PREFIX+":state:"+id));
    }

    @Test void realRedisRejectsStaleCasAndRecoversCompleteState() {
        String id=create();GameState first=repository.find(id),second=repository.find(id);
        first.version=1;second.version=1;
        assertTrue(repository.commit(first,0,null));assertFalse(repository.commit(second,0,null));
        assertEquals(1,repository.find(id).version);assertEquals(8,repository.find(id).pieces.size());
        assertEquals(1,games.create(input(id).command()).version());
        assertThrows(GameException.class,()->games.create(new CreateGame(id,"different-lobby","classic",input(id).participants(),"test-a").command()));
    }
    @Test void realRedisLegacyLayoutIsRejectedBeforeReceiptsAndWithoutWrites() {
        String id=create();
        String key=PREFIX+":state:"+id;
        for(String layout:Arrays.asList(null,"v1","v2-100","v3-100","unknown")) {
            var old=repository.find(id);
            old.boardLayout=layout;
            old.receipts.put("test-a:old",new GameState.Receipt("legacy",7));
            String before=json.writeValueAsString(old);
            redis.opsForHash().put(key,"state",before);
            assertEquals("UNSUPPORTED_BOARD_LAYOUT",assertThrows(GameException.class,
                    ()->games.synchronization(id,"test-a")).code());
            assertEquals("UNSUPPORTED_BOARD_LAYOUT",assertThrows(GameException.class,
                    ()->games.action("test-a",new Action("ROLL_DICE",id,"old",0L,
                            null,null,null,null,null,null,null,null,null,null).command())).code());
            assertEquals("UNSUPPORTED_BOARD_LAYOUT",assertThrows(GameException.class,
                    ()->games.create(input(id).command())).code());
            assertEquals(before,redis.opsForHash().get(key,"state"));
            assertEquals("0",redis.opsForHash().get(key,"version"));
            assertTrue(redis.opsForSet().isMember(PREFIX+":active",id));
        }
    }
    @Test void realRedisRecoversReleaseCountersAndPausedTimers() {
        String id=create();var state=repository.find(id);
        state.turn.consecutiveDoubles=2;state.turn.homeAttempts=1;
        state.turn.homeRelease=true;state.turn.extraRollAvailable=true;
        state.turn.rollId="persisted-roll";state.turn.consumed=new boolean[]{true,true};
        state.turn.movementRemainingMillis=18_000L;state.turn.pausedMovementRollId=state.turn.rollId;
        state.trivia=new GameState.PendingTrivia();state.trivia.id="question";
        state.trivia.deadline=System.currentTimeMillis()+15_000;
        state.version++;
        assertTrue(repository.commit(state,0,null));var restored=repository.find(id);
        assertEquals(2,restored.turn.consecutiveDoubles);assertEquals(1,restored.turn.homeAttempts);
        assertTrue(restored.turn.homeRelease);assertTrue(restored.turn.extraRollAvailable);
        assertArrayEquals(new boolean[]{true,true},restored.turn.consumed);
        assertEquals(18_000,restored.turn.movementRemainingMillis);
        assertEquals(restored.turn.rollId,restored.turn.pausedMovementRollId);
        assertEquals(state.trivia.deadline,restored.trivia.deadline);
    }
    @Test void realRedisRejectsCommitOfUnknownGame() {
        GameRepositoryContractTest.verifyMissingCommit(repository, "absent-" + UUID.randomUUID());
    }
    @Test void realRedisRecoversAppliedDiceEffectsWithoutLosingDuration() {
        String id=create();GameState working=repository.find(id);
        working.player("test-a").effects.add(new GameState.Effect("DICE_MAX_LIMIT",3,2,3));
        working.player("test-a").effects.add(new GameState.Effect("CHOOSE_DICE",1,2,6));
        working.version=1;
        assertTrue(repository.commit(working,0,null));
        var effects=repository.find(id).player("test-a").effects;
        assertEquals(2,effects.size());
        assertEquals(3,effects.getFirst().remainingTurns);
        assertEquals(2,effects.getFirst().startsAtTurn);
        assertEquals(3,effects.getFirst().maxValue);
        assertEquals("CHOOSE_DICE",effects.getLast().code);
        assertEquals(1,effects.getLast().remainingTurns);
    }
    @Test void eachPublishedCardResolvesAgainstMongoAndPersistsInRedis() {
        for (String cardId : List.of("kill-next","kill-chosen","advance-safe","instant-death","skip-turn","doubles-death")) {
            String id=create();
            assertTrue(games.system(id, state -> {
                var own = state.piece("seat-0-piece-0");
                own.progress=67;own.position=4;own.status="BOARD";
                var rival = state.piece("seat-1-piece-0");
                rival.progress=51;rival.position=5;rival.status="BOARD";
                var events = new ArrayList<RuleEngine.Event>();
                ((TriviaRules) rules).grant(state,catalog.card(cardId),"test-a",events);
                return events;
            }),cardId);
            var state = repository.find(id);
            switch (cardId) {
                case "kill-next" -> assertEquals("HOME",state.piece("seat-1-piece-0").status);
                case "instant-death" -> assertEquals("HOME",state.piece("seat-0-piece-0").status);
                case "kill-chosen", "advance-safe" -> {
                    var choice=state.card.choices.getFirst();
                    var request=new Action("CARD_TARGET",id,"resolve",state.version,
                            choice.pieceId(),null,null,null,choice.targetPosition(),null,state.card.id,null,
                            choice.targetPieceId(),choice.targetPlayerId());
                    assertFalse(games.action("test-a",request.command()).duplicate());
                    var result=repository.find(id);
                    assertNull(result.card);
                    if (cardId.equals("kill-chosen")) assertEquals("HOME",result.piece("seat-1-piece-0").status);
                    else assertEquals(5,result.piece("seat-0-piece-0").position);
                    assertTrue(games.action("test-a",request.command()).duplicate());
                }
                case "skip-turn" -> {
                    assertEquals("SKIP_NEXT_TURN",state.player("test-a").effects.getFirst().code);
                    advanceByExpiry(id);
                    assertEquals("test-b",repository.find(id).turn.playerId);
                    advanceByExpiry(id);
                    var after=repository.find(id);
                    assertEquals("test-b",after.turn.playerId);
                    assertTrue(after.player("test-a").effects.isEmpty());
                }
                case "doubles-death" -> {
                    assertEquals("DOUBLES_PENALTY",state.player("test-a").effects.getFirst().code);
                    advanceByExpiry(id);
                    var waiting=repository.find(id);
                    games.action("test-b",new Action("ROLL_DICE",id,"doubles",waiting.version,
                            null,null,null,null,null,null,null,null,null,null).command());
                    assertEquals("HOME",repository.find(id).piece("seat-0-piece-0").status);
                }
            }
        }
    }
    private void advanceByExpiry(String id) {
        assertTrue(games.system(id, state -> {
            state.turn.deadline = 0;
            return rules.tick(state);
        }));
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
    @ParameterizedTest @CsvSource({"classic,2","science,2","classic,3","science,3","classic,4","science,4"})
    void twoConcurrentHumansAlternateObserveMovesAndReconnect(String map,int count) throws Exception {
        String id="it-"+UUID.randomUUID();ids.add(id);
        var participants=new ArrayList<GameSetup.Participant>();
        for(int seat=0;seat<count;seat++) participants.add(new GameSetup.Participant("human-"+seat,false,seat));
        games.create(new GameSetup(id,"shared-room",map,participants,"human-0"));
        try(var client=HttpClient.newHttpClient()) {
            Inbox[] inboxes={new Inbox(),new Inbox()};
            WebSocket[] sockets=new WebSocket[2];
            try {
                for(int index=0;index<2;index++) {
                    sockets[index]=client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/game"),inboxes[index]).join();
                    send(sockets[index],Map.of("type","AUTH","token",token("human-"+index)));
                    inboxes[index].await("AUTHENTICATED");
                    send(sockets[index],Map.of("type","JOIN_GAME","gameId",id));
                    assertTrue(inboxes[index].await("GAME_STATE").contains(id));
                }
                for(int index=0;index<2;index++) {
                    String actor="human-"+index;
                    var state=games.load(id);assertEquals(actor,state.turn.playerId);
                    send(sockets[index],Map.of("type","ROLL_DICE","gameId",id,"requestId","roll-"+index,"expectedVersion",state.version));
                    inboxes[index].await("ACTION_ACK");
                    var pending=games.load(id);
                    send(sockets[index],new Action("DICE_ANIMATION_COMPLETED",id,"animation-"+index,pending.version,
                            null,null,null,null,null,null,pending.turn.rollId,null,null,null));
                    inboxes[index].await("ACTION_ACK");
                    prepareMovementRoll(id, actor, sockets[index], inboxes[index]);
                    var move=rules.calculate(games.load(id),actor,"seat-"+index+"-piece-0",0);
                    assertEquals(index*17+5,move.from());assertEquals(index*17+9,move.to());
                    send(sockets[index],new Action("MOVE_REQUEST",id,"move-"+index,games.load(id).version,
                            move.pieceId(),0,move.dieValue(),move.from(),move.to(),null,null,null,null,null));
                    inboxes[index].await("ACTION_ACK");
                    String publication;
                    do { publication=inboxes[1-index].await("GAME_UPDATED"); } while(!publication.contains("PIECE_MOVED"));
                    assertTrue(publication.contains(move.pieceId()));
                    send(sockets[index],Map.of("type","END_TURN","gameId",id,"requestId","end-"+index,"expectedVersion",games.load(id).version));
                    inboxes[index].await("ACTION_ACK");
                    assertEquals("human-"+((index+1)%count),games.load(id).turn.playerId);
                }
            } finally {
                for(var socket:sockets) if(socket!=null) socket.sendClose(1000,"reconnect").join();
            }
            var reconnected=new Inbox();
            var socket=client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/game"),reconnected).join();
            try {
                send(socket,Map.of("type","AUTH","token",token("human-0")));reconnected.await("AUTHENTICATED");
                send(socket,Map.of("type","SYNC_REQUEST","gameId",id));
                var state=json.readTree(reconnected.await("GAME_STATE")).path("state");
                assertEquals(id,state.path("gameId").asString());assertEquals(map,state.path("mapId").asString());
                assertEquals(count,state.path("players").size());
                assertEquals(9,games.load(id).piece("seat-0-piece-0").position);
                assertEquals(26,games.load(id).piece("seat-1-piece-0").position);
            } finally {socket.sendClose(1000,"done").join();}
        }
    }
    private void prepareMovementRoll(String id, String actor, WebSocket socket, Inbox inbox) throws Exception {
        var released=games.load(id);
        assertTrue(released.turn.homeRelease);
        assertTrue(released.turn.extraRollAvailable);
        assertArrayEquals(new boolean[]{true,true},released.turn.consumed);
        assertTrue(games.moves(id,actor).isEmpty());
        games.system(id, state -> {
            state.pieces.stream().filter(p -> p.ownerId.equals(actor) && p.status.equals("HOME"))
                    .forEach(p -> {p.status="CROWNED";p.progress=Board.FINISH;p.position=new Board().position(state.player(actor).seat,p.number,Board.FINISH);});
            return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        send(socket,new Action("ROLL_DICE",id,"movement-roll",games.load(id).version,
                null,null,null,null,null,null,null,null,null,null));
        inbox.await("ACTION_ACK");
        var waiting=games.load(id);
        send(socket,new Action("DICE_ANIMATION_COMPLETED",id,"movement-animation",waiting.version,
                null,null,null,null,null,null,waiting.turn.rollId,null,null,null));
        inbox.await("ACTION_ACK");
    }
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
                var pending=games.load(id);
                send(wsA,new Action("DICE_ANIMATION_COMPLETED",id,"animation",pending.version,
                        null,null,null,null,null,null,pending.turn.rollId,null,null,null));
                a.await("ACTION_ACK");b.await("GAME_UPDATED");
                prepareMovementRoll(id, "test-a", wsA, a);
                var m=rules.calculate(games.load(id),"test-a","seat-0-piece-0",0);
                var move=new Action("MOVE_REQUEST",id,"move-1",games.load(id).version,m.pieceId(),m.dieIndex(),m.dieValue(),m.from(),m.to(),null,null,null,null,null);
                send(wsA,move);a.await("ACTION_ACK");String moved;
                do { moved=b.await("GAME_UPDATED"); } while(!moved.contains("PIECE_MOVED"));
                assertEquals(9,games.snapshot(id,"test-b").pieces().getFirst().position);
                send(wsA,move);assertTrue(a.await("ACTION_ACK").contains("\"duplicate\":true"));assertEquals(move.expectedVersion()+1,games.load(id).version);
                assertTrue(presence.connected(id,"test-a"));
                send(wsB,Map.of("type","SYNC_REQUEST","gameId",id));assertTrue(b.await("GAME_STATE").contains("\"version\":"+games.load(id).version));
            } finally {wsA.sendClose(1000,"done").join();wsB.sendClose(1000,"done").join();}
        }
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void partialReleaseRecoveryAndThreePairsUseTwoJwtWebSocketsWithoutFourthRoll(String map) throws Exception {
        String id="it-"+UUID.randomUUID();ids.add(id);
        games.create(new CreateGame(id,"partial-release",map,input(id).participants(),"test-a").command());
        games.system(id,state->{
            int[] positions={12,25,39};
            for(int n=1;n<4;n++) {
                var piece=state.piece("seat-0-piece-"+n);piece.status="BOARD";
                piece.progress=positions[n-1]-5;piece.position=positions[n-1];
            }
            state.player("test-a").effects.add(new GameState.Effect("CHOOSE_DICE",1,1,6));
            return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        try(var client=HttpClient.newHttpClient()) {
            var a=new Inbox();var b=new Inbox();
            var wsA=client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/game"),a).join();
            var wsB=client.newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws/game"),b).join();
            try {
                for(var entry:List.of(Map.entry(wsA,Map.entry(a,"test-a")),Map.entry(wsB,Map.entry(b,"test-b")))) {
                    send(entry.getKey(),Map.of("type","AUTH","token",token(entry.getValue().getValue())));
                    entry.getValue().getKey().await("AUTHENTICATED");
                    send(entry.getKey(),Map.of("type","JOIN_GAME","gameId",id));entry.getValue().getKey().await("GAME_STATE");
                }
                send(wsA,new Action("ROLL_DICE",id,"partial-roll",games.load(id).version,
                        null,null,null,null,null,List.of(5,5),null,null,null,null));a.await("ACTION_ACK");
                var waiting=repository.find(id);String rollId=waiting.turn.rollId;
                assertArrayEquals(new boolean[]{true,false},waiting.turn.consumed);
                assertEquals(Set.of("seat-0-piece-0"),waiting.turn.releasedPieceIds);
                send(wsA,new Action("DICE_ANIMATION_COMPLETED",id,"partial-animation",waiting.version,
                        null,null,null,null,null,null,rollId,null,null,null));a.await("ACTION_ACK");
                // Fresh application service reads the real Redis aggregate, not an in-memory copy.
                var recovered=new GameService(repository,rules,new JsonPayloadFingerprints(json));
                long deadline=recovered.load(id).turn.deadline;
                games.system(id,state->{state.player("test-a").disconnectedAt=System.currentTimeMillis();
                    return List.of(RuleEngine.Event.of("TEST_DISCONNECTED"));});
                send(wsA,Map.of("type","SYNC_REQUEST","gameId",id));var sync=json.readTree(a.await("GAME_STATE"));
                assertEquals(rollId,sync.path("state").path("turn").path("rollId").asString());
                assertEquals(deadline,recovered.load(id).turn.deadline);
                assertFalse(sync.path("legalMoves").isEmpty());
                sync.path("legalMoves").forEach(m->{assertNotEquals("seat-0-piece-0",m.path("pieceId").asString());
                    assertEquals(1,m.path("dieIndex").asInt());assertEquals(5,m.path("dieValue").asInt());});
                long version=recovered.load(id).version;
                send(wsA,new Action("MOVE_REQUEST",id,"forged-release",version,"seat-0-piece-0",1,5,5,10,null,null,null,null,null));
                assertTrue(a.await("ERROR").contains("PIECE_RELEASED_THIS_ROLL"));assertEquals(version,recovered.load(id).version);
                var move=rules.calculate(recovered.load(id),"test-a","seat-0-piece-1",1);
                var request=new Action("MOVE_REQUEST",id,"remaining",version,move.pieceId(),1,5,move.from(),move.to(),null,null,null,null,null);
                send(wsA,request);a.await("ACTION_ACK");
                send(wsA,request);assertTrue(a.await("ACTION_ACK").contains("\"duplicate\":true"));
                assertTrue(recovered.load(id).turn.extraRollAvailable);assertEquals(1,recovered.load(id).turn.consecutiveDoubles);
                for(int pair=2;pair<=3;pair++) {
                    send(wsA,new Action("ROLL_DICE",id,"pair-"+pair,games.load(id).version,
                            null,null,null,null,null,null,null,null,null,null));a.await("ACTION_ACK");
                    var state=recovered.load(id);
                    send(wsA,new Action("DICE_ANIMATION_COMPLETED",id,"animation-"+pair,state.version,
                            null,null,null,null,null,null,state.turn.rollId,null,null,null));a.await("ACTION_ACK");
                    assertEquals(pair,recovered.load(id).turn.consecutiveDoubles);
                    if(pair==2) {
                        // The formerly excluded piece is now an ordinary legal option.
                        assertTrue(recovered.moves(id,"test-a").stream().anyMatch(m->m.pieceId().equals("seat-0-piece-0")));
                        var sum=rules.calculate(recovered.load(id),"test-a","seat-0-piece-2",2);
                        send(wsA,new Action("MOVE_REQUEST",id,"second-pair-move",recovered.load(id).version,
                                sum.pieceId(),2,sum.dieValue(),sum.from(),sum.to(),null,null,null,null,null));a.await("ACTION_ACK");
                    }
                }
                var reward=recovered.load(id);assertTrue(reward.turn.crownChoiceRequired);
                assertTrue(recovered.moves(id,"test-a").isEmpty());assertArrayEquals(new boolean[]{true,true},reward.turn.consumed);
                var crown=new Action("CROWN_REQUEST",id,"third-pair-crown",reward.version,
                        "seat-0-piece-1",null,null,null,null,null,null,null,null,null);
                send(wsA,crown);a.await("ACTION_ACK");send(wsA,crown);
                assertTrue(a.await("ACTION_ACK").contains("\"duplicate\":true"));
                var closed=recovered.load(id);assertEquals("test-b",closed.turn.playerId);assertEquals(0,closed.turn.consecutiveDoubles);
                assertNull(closed.turn.rollId);assertNull(closed.turn.movementStartedAt);assertNull(closed.turn.movementRemainingMillis);
                send(wsA,new Action("ROLL_DICE",id,"fourth-rejected",closed.version,
                        null,null,null,null,null,null,null,null,null,null));
                assertTrue(a.await("ERROR").contains("NOT_YOUR_TURN"));
                send(wsB,Map.of("type","SYNC_REQUEST","gameId",id));
                assertEquals("test-b",json.readTree(b.await("GAME_STATE")).path("state").path("turn").path("playerId").asString());
                assertEquals(closed.version,recovered.load(id).version);
            } finally {wsA.sendClose(1000,"done").join();wsB.sendClose(1000,"done").join();}
        }
    }
    @Test void realJvmRestartKeepsPendingPartialDieAndRejectsReleasedPiece() throws Exception {
        String id=create();
        games.system(id,state->{
            for(int n=1;n<4;n++) {
                var piece=state.piece("seat-0-piece-"+n);piece.status="BOARD";
                piece.progress=7+n*10;piece.position=piece.progress+5;
            }
            state.player("test-a").effects.add(new GameState.Effect("CHOOSE_DICE",1,1,6));
            return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        int childPort;
        try(var reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {childPort=reservation.getLocalPort();}
        Process child=null;
        try(var client=HttpClient.newHttpClient()) {
            child=startRecoveryProcess(childPort,client);
            var inbox=new Inbox();
            var socket=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+childPort+"/ws/game"),inbox).join();
            try {
                send(socket,Map.of("type","AUTH","token",token("test-a")));inbox.await("AUTHENTICATED");
                send(socket,Map.of("type","JOIN_GAME","gameId",id));inbox.await("GAME_STATE");
                send(socket,new Action("ROLL_DICE",id,"process-roll",games.load(id).version,
                        null,null,null,null,null,List.of(5,5),null,null,null,null));inbox.await("ACTION_ACK");
                var pending=games.load(id);
                send(socket,new Action("DICE_ANIMATION_COMPLETED",id,"process-animation",pending.version,
                        null,null,null,null,null,null,pending.turn.rollId,null,null,null));inbox.await("ACTION_ACK");
            } finally {socket.sendClose(1000,"restart").join();}
            var persisted=repository.find(id);
            assertArrayEquals(new boolean[]{true,false},persisted.turn.consumed);
            stopRecoveryProcess(child);child=null;
            child=startRecoveryProcess(childPort,client);
            var restoredInbox=new Inbox();
            var restored=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+childPort+"/ws/game"),restoredInbox).join();
            try {
                send(restored,Map.of("type","AUTH","token",token("test-a")));restoredInbox.await("AUTHENTICATED");
                send(restored,Map.of("type","SYNC_REQUEST","gameId",id));
                var sync=json.readTree(restoredInbox.await("GAME_STATE"));
                assertEquals(persisted.turn.rollId,sync.path("state").path("turn").path("rollId").asString());
                assertEquals(persisted.turn.deadline,sync.path("state").path("turn").path("deadline").asLong());
                assertEquals(Set.of("seat-0-piece-0"),repository.find(id).turn.releasedPieceIds);
                assertFalse(sync.path("legalMoves").isEmpty());
                sync.path("legalMoves").forEach(m->{assertNotEquals("seat-0-piece-0",m.path("pieceId").asString());
                    assertEquals(1,m.path("dieIndex").asInt());});
                long version=games.load(id).version;
                send(restored,new Action("MOVE_REQUEST",id,"process-forged",version,
                        "seat-0-piece-0",1,5,5,10,null,null,null,null,null));
                assertTrue(restoredInbox.await("ERROR").contains("PIECE_RELEASED_THIS_ROLL"));
                assertEquals(version,games.load(id).version);
            } finally {restored.sendClose(1000,"done").join();}
        } finally {if(child!=null) stopRecoveryProcess(child);}
    }
    private Process startRecoveryProcess(int childPort,HttpClient client) throws Exception {
        var builder=new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                "-cp",System.getProperty("java.class.path"),GameServiceApplication.class.getName(),
                "--server.address=127.0.0.1","--server.port="+childPort,"--game.redis-prefix="+PREFIX,
                "--eureka.client.enabled=false","--game.scheduling.enabled=false","--game.seed.enabled=false");
        builder.environment().putAll(Map.of("REDIS_URL",System.getenv("GAME_TEST_REDIS_URL"),
                "MONGODB_URI",System.getenv("GAME_TEST_MONGO_URI"),"EUREKA_URL","http://127.0.0.1:8761/eureka/",
                "JWT_PUBLIC_KEY_PATH",Path.of("target/integration-public.pem").toAbsolutePath().toString(),
                "GAME_LOBBY_TOKEN","integration-credential-32-characters"));
        var process=builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(
                Path.of("target/partial-release-restart-process.log").toFile())).start();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            while(System.nanoTime()<until&&process.isAlive()) {
                try {
                    var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+childPort+"/actuator/health"))
                            .timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()==200&&json.readTree(response.body()).path("status").asString().equals("UP")) return process;
                } catch(java.io.IOException unavailable) { /* Only this isolated child is still starting. */ }
                Thread.sleep(100);
            }
            throw new AssertionError("Recovery process failed; inspect target/partial-release-restart-process.log");
        } catch(Exception|AssertionError failure) {stopRecoveryProcess(process);throw failure;}
    }
    private void stopRecoveryProcess(Process process) throws InterruptedException {
        process.destroy();
        if(!process.waitFor(10,TimeUnit.SECONDS)) {
            process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS));
        }
    }
    private String captureGame(String map) {
        String id="it-"+UUID.randomUUID();ids.add(id);
        games.create(new CreateGame(id,"capture-lock",map,input(id).participants(),"test-a").command());
        games.system(id,state->{
            for(int n=0;n<4;n++) {
                var piece=state.piece("seat-0-piece-"+n);
                piece.status=n<2?"BOARD":"CROWNED";
                piece.progress=n==0?1:n==1?15:Board.FINISH;
                piece.position=new Board().position(0,n,piece.progress);
            }
            for(int n=0;n<2;n++) {
                var victim=state.piece("seat-1-piece-"+n);victim.status="BOARD";
                victim.position=n==0?10:13;victim.progress=Math.floorMod(victim.position-22,68);
            }
            state.player("test-a").effects.add(new GameState.Effect("CHOOSE_DICE",1,1,6));
            state.turn.diceSelectionRequired=true;
            return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        return id;
    }
    private void captureOverWebSocket(String id,URI uri,String sample) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var a=new Inbox();var b=new Inbox();
            var wsA=client.newWebSocketBuilder().buildAsync(uri,a).join();
            var wsB=client.newWebSocketBuilder().buildAsync(uri,b).join();
            try {
                for(var entry:List.of(Map.entry(wsA,Map.entry(a,"test-a")),Map.entry(wsB,Map.entry(b,"test-b")))) {
                    send(entry.getKey(),Map.of("type","AUTH","token",token(entry.getValue().getValue())));
                    entry.getValue().getKey().await("AUTHENTICATED");
                    send(entry.getKey(),Map.of("type","JOIN_GAME","gameId",id));entry.getValue().getKey().await("GAME_STATE");
                }
                send(wsA,new Action("ROLL_DICE",id,"capture-roll",games.load(id).version,
                        null,null,null,null,null,List.of(4,3),null,null,null,null));a.await("ACTION_ACK");
                var waiting=repository.find(id);String roll=waiting.turn.rollId;
                send(wsA,new Action("DICE_ANIMATION_COMPLETED",id,"capture-animation",waiting.version,
                        null,null,null,null,null,null,roll,null,null,null));a.await("ACTION_ACK");
                long deadline=repository.find(id).turn.deadline;
                var capture=new Action("MOVE_REQUEST",id,"capture",games.load(id).version,
                        "seat-0-piece-0",0,4,6,10,null,null,null,null,null);
                send(wsA,capture);var ack=json.readTree(a.await("ACTION_ACK"));
                String publication;
                do{publication=b.await("GAME_UPDATED");}while(!publication.contains("PIECE_CAPTURED"));
                assertTrue(publication.contains("\"byPieceId\":\"seat-0-piece-0\""));
                var recovered=new GameService(repository,rules,new JsonPayloadFingerprints(json));
                assertEquals(Set.of("seat-0-piece-0"),recovered.load(id).turn.captureLockedPieceIds);
                assertArrayEquals(new boolean[]{true,false},recovered.load(id).turn.consumed);
                assertEquals(deadline,recovered.load(id).turn.deadline);
                games.system(id,state->{state.player("test-a").disconnectedAt=System.currentTimeMillis();
                    return List.of(RuleEngine.Event.of("TEST_DISCONNECTED"));});
                send(wsA,Map.of("type","SYNC_REQUEST","gameId",id));var sync=json.readTree(a.await("GAME_STATE"));
                assertFalse(sync.path("legalMoves").isEmpty());
                sync.path("legalMoves").forEach(m->{assertEquals("seat-0-piece-1",m.path("pieceId").asString());
                    assertEquals(1,m.path("dieIndex").asInt());assertEquals(3,m.path("dieValue").asInt());});
                long version=recovered.load(id).version;
                var forged=new Action("MOVE_REQUEST",id,"capture-forged",version,
                        "seat-0-piece-0",1,3,10,13,null,null,null,null,null);
                send(wsA,forged);var error=json.readTree(a.await("ERROR"));
                assertEquals("PIECE_CAPTURE_LOCKED",error.path("code").asString());
                assertEquals(version,recovered.load(id).version);assertEquals("BOARD",recovered.load(id).piece("seat-1-piece-1").status);
                send(wsB,forged);assertTrue(b.await("ERROR").contains("NOT_YOUR_TURN"));
                send(wsA,capture);assertTrue(a.await("ACTION_ACK").contains("\"duplicate\":true"));
                assertEquals(version,recovered.load(id).version);
                Files.writeString(Path.of("target/capture-wire-"+sample+".json"),json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(Map.of("captureRequest",capture,"captureAck",ack,
                                "capturePublication",json.readTree(publication),"synchronization",sync,"forgedRequest",forged,"error",error)));
                send(wsA,new Action("MOVE_REQUEST",id,"other-piece",version,
                        "seat-0-piece-1",1,3,20,23,null,null,null,null,null));a.await("ACTION_ACK");
                assertEquals("test-b",recovered.load(id).turn.playerId);
                assertTrue(recovered.load(id).turn.captureLockedPieceIds.isEmpty());
            } finally {wsA.sendClose(1000,"done").join();wsB.sendClose(1000,"done").join();}
        }
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void captureLockUsesRealRedisAndTwoJwtGameWebSockets(String map) throws Exception {
        captureOverWebSocket(captureGame(map),URI.create("ws://127.0.0.1:"+port+"/ws/game"),"game-"+map);
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    @EnabledIfEnvironmentVariable(named="GAME_TEST_GATEWAY_JAR",matches=".+")
    void captureLockUsesRealGatewayAndGameWebSockets(String map) throws Exception {
        int gatewayPort;
        try(var reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {gatewayPort=reservation.getLocalPort();}
        var jar=Path.of(System.getenv("GAME_TEST_GATEWAY_JAR")).toAbsolutePath();assertTrue(Files.isRegularFile(jar));
        var builder=new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),"-jar",jar.toString(),
                "--server.address=127.0.0.1","--server.port="+gatewayPort,"--eureka.client.enabled=false",
                "--spring.cloud.discovery.client.simple.instances.GAME-SERVICE[0].uri=http://127.0.0.1:"+port);
        builder.environment().putAll(Map.of("JWT_PUBLIC_KEY_PATH",Path.of("target/integration-public.pem").toAbsolutePath().toString(),
                "EUREKA_URL","http://127.0.0.1:8761/eureka/"));
        var child=builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(
                Path.of("target/capture-gateway-process.log").toFile())).start();
        try(var client=HttpClient.newHttpClient()) {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);boolean ready=false;
            while(System.nanoTime()<until&&child.isAlive()) {
                try {
                    var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+gatewayPort+"/actuator/health"))
                            .timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString());
                    if(response.statusCode()==200){ready=true;break;}
                }catch(java.io.IOException starting){ /* Isolated Gateway is starting. */ }
                Thread.sleep(100);
            }
            assertTrue(ready,"Gateway unavailable; inspect target/capture-gateway-process.log");
            captureOverWebSocket(captureGame(map),URI.create("ws://127.0.0.1:"+gatewayPort+"/api/game/ws"),"gateway-"+map);
            noMovesOverWebSocket(map,URI.create("ws://127.0.0.1:"+gatewayPort+"/api/game/ws"));
            finalLaneOverWebSocket(map,URI.create("ws://127.0.0.1:"+gatewayPort+"/api/game/ws"));
        } finally {stopRecoveryProcess(child);}
    }

    private void noMovesOverWebSocket(String map,URI uri) throws Exception {
        String id=captureGame(map);
        games.system(id,state->{
            var piece=state.piece("seat-0-piece-1");piece.progress=75;piece.position=74;
            return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        try(var client=HttpClient.newHttpClient()) {
            var a=new Inbox();var b=new Inbox();
            var wsA=client.newWebSocketBuilder().buildAsync(uri,a).join();
            var wsB=client.newWebSocketBuilder().buildAsync(uri,b).join();
            try {
                for(var entry:List.of(Map.entry(wsA,Map.entry(a,"test-a")),Map.entry(wsB,Map.entry(b,"test-b")))) {
                    send(entry.getKey(),Map.of("type","AUTH","token",token(entry.getValue().getValue())));
                    entry.getValue().getKey().await("AUTHENTICATED");
                    send(entry.getKey(),Map.of("type","JOIN_GAME","gameId",id));entry.getValue().getKey().await("GAME_STATE");
                }
                send(wsA,new Action("ROLL_DICE",id,"no-moves-roll",games.load(id).version,
                        null,null,null,null,null,List.of(4,3),null,null,null,null));
                a.await("ACTION_ACK");b.await("GAME_UPDATED");
                var waiting=games.load(id);
                send(wsA,new Action("DICE_ANIMATION_COMPLETED",id,"no-moves-ack",waiting.version,
                        null,null,null,null,null,null,waiting.turn.rollId,null,null,null));
                a.await("ACTION_ACK");b.await("GAME_UPDATED");
                var request=new Action("MOVE_REQUEST",id,"no-moves-capture",games.load(id).version,
                        "seat-0-piece-0",0,4,6,10,null,null,null,null,null);
                long started=System.nanoTime();send(wsA,request);a.await("ACTION_ACK");
                String publication=b.await("GAME_UPDATED");long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                var update=json.readTree(publication);var events=new ArrayList<String>();
                update.path("events").forEach(e->events.add(e.path("type").asString()));
                assertEquals(List.of("PIECE_CAPTURED","PIECE_MOVED","TURN_CHANGED"),events);
                assertEquals("test-b",update.path("state").path("turn").path("playerId").asString());
                assertTrue(update.path("state").path("turn").path("dice").isEmpty());
                assertTrue(update.path("state").path("turn").path("movementStartedAt").isNull());
                assertEquals(request.expectedVersion()+1,update.path("version").asLong());
                var recovered=new RedisGameRepository(redis,json,PREFIX).find(id);
                assertEquals("test-b",recovered.turn.playerId);assertEquals(75,recovered.piece("seat-0-piece-1").progress);
                send(wsA,request);assertTrue(json.readTree(a.await("ACTION_ACK")).path("duplicate").asBoolean());
                send(wsA,Map.of("type","SYNC_REQUEST","gameId",id));
                var sync=json.readTree(a.await("GAME_STATE"));assertTrue(sync.path("legalMoves").isEmpty());
                assertEquals(recovered.version,sync.path("state").path("version").asLong());
                Files.writeString(Path.of("target/no-moves-gateway-"+map+".json"),publication);
                System.out.println("NO_MOVES_GATEWAY map="+map+" captureToTurnChanged="+elapsed+"ms version="+recovered.version);
            } finally {wsA.sendClose(1000,"done").join();wsB.sendClose(1000,"done").join();}
        }
    }

    private void finalLaneOverWebSocket(String map,URI uri) throws Exception {
        int[][] approaches={{2,3,4,5,68},{19,20,21,22,76},{36,37,38,39,84},{53,54,55,56,92}};
        var evidence=new ArrayList<Object>();
        try(var client=HttpClient.newHttpClient()) {
            for(int seat=0;seat<4;seat++) for(int distance=1;distance<=4;distance++) {
                var a=new Inbox();var b=new Inbox();
                var wsA=client.newWebSocketBuilder().buildAsync(uri,a).join();
                var wsB=client.newWebSocketBuilder().buildAsync(uri,b).join();
                try {
                    send(wsA,Map.of("type","AUTH","token",token("test-a")));a.await("AUTHENTICATED");
                    send(wsB,Map.of("type","AUTH","token",token("test-b")));b.await("AUTHENTICATED");
                    String id="it-"+UUID.randomUUID();ids.add(id);int currentSeat=seat,currentDistance=distance;
                    games.create(new GameSetup(id,"lane-fixture",map,List.of(
                            new GameSetup.Participant("test-a",false,seat),
                            new GameSetup.Participant("test-b",false,(seat+1)%4)),"test-a"));
                    String pieceId="seat-"+seat+"-piece-0";
                    games.system(id,state->{
                        var piece=state.piece(pieceId);piece.status="BOARD";piece.progress=69-currentDistance;
                        piece.position=approaches[currentSeat][4-currentDistance];
                        state.turn.rolled=true;state.turn.dice=new int[]{currentDistance,1};
                        state.turn.consumed=new boolean[]{false,true};state.turn.animationStatus="COMPLETED";
                        state.turn.rollId=UUID.randomUUID().toString();state.turn.movementStartedAt=System.currentTimeMillis();
                        state.turn.deadline=state.turn.movementStartedAt+30_000;
                        return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
                    });
                    send(wsA,Map.of("type","JOIN_GAME","gameId",id));a.await("GAME_STATE");
                    send(wsB,Map.of("type","JOIN_GAME","gameId",id));b.await("GAME_STATE");
                    var request=new Action("MOVE_REQUEST",id,"lane-move",games.load(id).version,pieceId,
                            0,distance,approaches[seat][4-distance],approaches[seat][4],null,null,null,null,null);
                    send(wsA,request);a.await("ACTION_ACK");var publication=json.readTree(b.await("GAME_UPDATED"));
                    var moved=publication.path("events").get(0);assertEquals("PIECE_MOVED",moved.path("type").asString());
                    assertEquals(approaches[seat][4-distance],moved.path("data").path("from").asInt());
                    assertEquals(approaches[seat][4],moved.path("data").path("to").asInt());
                    assertEquals(69,repository.find(id).piece(pieceId).progress);
                    evidence.add(Map.of("seat",seat,"fromProgress",69-distance,"die",distance,
                            "expectedSteps",Arrays.stream(approaches[seat],5-distance,5).boxed().toList(),"publication",publication));
                } finally {wsA.sendClose(1000,"done").join();wsB.sendClose(1000,"done").join();}
            }
        }
        Files.writeString(Path.of("target/final-lane-gateway-"+map+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }

    @ParameterizedTest @CsvSource({"classic","science"})
    void realRedisRecoveryClosesImpossibleRemainderWithoutTimeout(String map) {
        String id=captureGame(map);
        games.system(id,state->{
            var nearCrown=state.piece("seat-0-piece-1");nearCrown.progress=75;nearCrown.position=74;
            state.turn.rolled=true;state.turn.dice=new int[]{4,3};state.turn.consumed=new boolean[]{true,false};
            state.turn.animationStatus="COMPLETED";state.turn.captureSatisfied=true;
            state.turn.captureLockedPieceIds.add("seat-0-piece-0");state.turn.movementStartedAt=System.currentTimeMillis();
            state.turn.deadline=state.turn.movementStartedAt+30_000;
            return List.of(RuleEngine.Event.of("TEST_RECOVERY_FIXTURE"));
        });
        var freshRepository=new RedisGameRepository(redis,json,PREFIX);
        var freshService=new GameService(freshRepository,rules,new JsonPayloadFingerprints(json));
        long version=freshService.load(id).version;
        assertTrue(freshService.moves(id,"test-a").isEmpty());
        assertTrue(freshService.system(id,rules::tick));assertFalse(freshService.system(id,rules::tick));
        assertEquals(version+1,freshService.load(id).version);assertEquals("test-b",freshService.load(id).turn.playerId);
        assertNull(freshService.load(id).turn.movementStartedAt);
    }
    @Test void realJvmRestartKeepsCaptureLockAndRemainingDie() throws Exception {
        String id=captureGame("classic");
        games.action("test-a",new Action("ROLL_DICE",id,"restart-roll",games.load(id).version,
                null,null,null,null,null,List.of(4,3),null,null,null,null).command());
        var waiting=games.load(id);
        games.action("test-a",new Action("DICE_ANIMATION_COMPLETED",id,"restart-animation",waiting.version,
                null,null,null,null,null,null,waiting.turn.rollId,null,null,null).command());
        games.action("test-a",new Action("MOVE_REQUEST",id,"restart-capture",games.load(id).version,
                "seat-0-piece-0",0,4,6,10,null,null,null,null,null).command());
        var persisted=repository.find(id);int childPort;
        try(var reservation=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {childPort=reservation.getLocalPort();}
        Process child=null;
        try(var client=HttpClient.newHttpClient()) {
            child=startRecoveryProcess(childPort,client);
            var firstRead=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+childPort+"/api/game/games/"+id))
                    .header("Authorization","Bearer "+token("test-a")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,firstRead.statusCode());
            assertEquals(1,json.readTree(firstRead.body()).path("turn").path("captureLockedPieceIds").size());
            stopRecoveryProcess(child);child=null;
            child=startRecoveryProcess(childPort,client);var inbox=new Inbox();
            var ws=client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+childPort+"/ws/game"),inbox).join();
            try {
                send(ws,Map.of("type","AUTH","token",token("test-a")));inbox.await("AUTHENTICATED");
                send(ws,Map.of("type","SYNC_REQUEST","gameId",id));var sync=json.readTree(inbox.await("GAME_STATE"));
                assertEquals(persisted.turn.rollId,sync.path("state").path("turn").path("rollId").asString());
                assertEquals(persisted.turn.deadline,sync.path("state").path("turn").path("deadline").asLong());
                assertArrayEquals(new boolean[]{true,false},repository.find(id).turn.consumed);
                assertEquals(Set.of("seat-0-piece-0"),repository.find(id).turn.captureLockedPieceIds);
                assertFalse(sync.path("legalMoves").isEmpty());
                sync.path("legalMoves").forEach(m->assertEquals("seat-0-piece-1",m.path("pieceId").asString()));
                send(ws,new Action("MOVE_REQUEST",id,"restart-forged",games.load(id).version,
                        "seat-0-piece-0",1,3,10,13,null,null,null,null,null));
                assertTrue(inbox.await("ERROR").contains("PIECE_CAPTURE_LOCKED"));
            }finally{ws.sendClose(1000,"done").join();}
        }finally{if(child!=null)stopRecoveryProcess(child);}
    }
    @ParameterizedTest @CsvSource({"classic","science"})
    void realRedisBotRunnerCapturesUsesAnotherPieceAndClosesThirdPair(String map) {
        String id=captureGame(map);
        games.system(id,state->{
            state.player("test-a").bot=true;state.player("test-a").effects.clear();
            state.player("test-a").effects.add(new GameState.Effect("DICE_MAX_LIMIT",5,1,4));
            state.turn.diceSelectionRequired=false;state.turn.maxDieValue=4;
            return List.of(RuleEngine.Event.of("TEST_FIXTURE"));
        });
        var policy=new com.pixelchiss.gameservice.bot.BotPolicy(rules,catalog,()->0.0);
        var runner=new com.pixelchiss.gameservice.bot.BotRunner(
                (com.pixelchiss.gameservice.application.ActiveGames)repository,games,policy);
        runner.step();
        long rolledVersion=repository.find(id).version;
        runner.step();assertEquals(rolledVersion,repository.find(id).version);
        advanceBotPause(id);runner.step();var captured=repository.find(id);
        assertEquals("HOME",captured.piece("seat-1-piece-0").status);
        assertEquals(Set.of("seat-0-piece-0"),captured.turn.captureLockedPieceIds);
        assertArrayEquals(new boolean[]{true,false},captured.turn.consumed);
        var next=policy.next(captured);assertEquals("seat-0-piece-1",next.pieceId());assertEquals(1,next.dieIndex());
        advanceBotPause(id);runner.step();assertTrue(repository.find(id).turn.extraRollAvailable);
        advanceBotPause(id);runner.step();assertEquals(2,repository.find(id).turn.consecutiveDoubles);
        assertTrue(repository.find(id).turn.captureLockedPieceIds.isEmpty());
        for(int step=0;step<8&&repository.find(id).turn.playerId.equals("test-a");step++) {
            advanceBotPause(id);runner.step();
        }
        var closed=repository.find(id);assertEquals("test-b",closed.turn.playerId);
        assertEquals(0,closed.turn.consecutiveDoubles);assertEquals(3,closed.pieces.stream()
                .filter(p->p.ownerId.equals("test-a")&&p.status.equals("CROWNED")).count());
        assertNull(policy.next(closed));assertNull(closed.turn.movementStartedAt);
    }
}
