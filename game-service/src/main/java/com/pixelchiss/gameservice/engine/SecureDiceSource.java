package com.pixelchiss.gameservice.engine;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class SecureDiceSource implements DiceSource {
    private final SecureRandom random = new SecureRandom();
    @Override public int roll(int maximum) { return random.nextInt(maximum) + 1; }
}
