package com.pixelchiss.gameservice.application;

/** Session leases are shared across Game instances by the production adapter. */
public interface Presence {
    void touch(String game, String player, String session);
    void remove(String game, String player, String session);
    boolean connected(String game, String player);
}
