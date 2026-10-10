package com.pixelchiss.gameservice.controller;

import com.pixelchiss.gameservice.engine.GameException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice
public class GameErrorHandler {
    public record Error(String code,String message) {}
    @ExceptionHandler(GameException.class) public ResponseEntity<Error> rule(GameException e) {
        HttpStatus status=switch(e.code()) {
            case "UNAUTHENTICATED" -> HttpStatus.UNAUTHORIZED;
            case "NOT_MEMBER","FORBIDDEN","BOT_IDENTITY" -> HttpStatus.FORBIDDEN;
            case "GAME_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "GAME_EXISTS","STALE_VERSION","CONCURRENT_UPDATE" -> HttpStatus.CONFLICT;
            case "INTEGRATION_DISABLED","CATALOG_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new Error(e.code(),e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<Error> invalid(Exception e){return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST","Invalid request fields"));}
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Error> storage(DataAccessException e){return ResponseEntity.status(503).body(new Error("SERVICE_UNAVAILABLE","Storage unavailable"));}
}
