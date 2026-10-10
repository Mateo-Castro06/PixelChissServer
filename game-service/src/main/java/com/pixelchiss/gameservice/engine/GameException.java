package com.pixelchiss.gameservice.engine;

public class GameException extends RuntimeException {
    private final String code;
    public GameException(String code, String message) { super(message); this.code = code; }
    public String code() { return code; }
    public static void require(boolean condition, String code, String message) {
        if (!condition) throw new GameException(code, message);
    }
}
