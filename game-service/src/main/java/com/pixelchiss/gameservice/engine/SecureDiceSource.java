package com.pixelchiss.gameservice.engine;

import java.security.SecureRandom;

public class SecureDiceSource implements DiceSource {
    private final SecureRandom random = new SecureRandom();
    @Override public int roll(int maximum) { return random.nextInt(maximum) + 1; }
}
