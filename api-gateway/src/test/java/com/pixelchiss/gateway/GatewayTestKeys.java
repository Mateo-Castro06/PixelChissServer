package com.pixelchiss.gateway;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

final class GatewayTestKeys {
    static final KeyPair KEYS = keys();
    static final String PUBLIC_KEY = publicKey();
    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static String publicKey() {
        try {
            Path path = Path.of("target/gateway-test-public.pem").toAbsolutePath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, "-----BEGIN PUBLIC KEY-----\n" +
                    Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(KEYS.getPublic().getEncoded()) +
                    "\n-----END PUBLIC KEY-----\n");
            return "file:" + path;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    static String token(long expiresInSeconds) throws Exception {
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .subject("test-player").issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(expiresInSeconds))).build());
        token.sign(new RSASSASigner(KEYS.getPrivate()));
        return token.serialize();
    }
}
