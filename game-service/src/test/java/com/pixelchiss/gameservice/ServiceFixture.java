package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.dto.*;
import com.pixelchiss.gameservice.engine.*;
import com.pixelchiss.gameservice.service.GameService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

abstract class ServiceFixture extends EngineFixture {
    MemoryGames repo;
    GameService service;
    @BeforeEach void setupService(){repo=new MemoryGames();service=new GameService(repo,engine,repo.json);repo.create(game,"{}");}
}
