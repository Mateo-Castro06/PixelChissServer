package com.pixelchiss.gameservice.persistence;

import com.pixelchiss.gameservice.application.PayloadFingerprints;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonPayloadFingerprints implements PayloadFingerprints {
    private final ObjectMapper json;

    public JsonPayloadFingerprints(ObjectMapper json) {
        this.json = json;
    }

    @Override public String fingerprint(Object command) {
        try {
            byte[] bytes = json.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
