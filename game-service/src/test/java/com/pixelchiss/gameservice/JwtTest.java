package com.pixelchiss.gameservice;

import com.pixelchiss.gameservice.config.JwtConfig;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

class JwtTest {
    KeyPair key() throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}
    String sign(KeyPair key,JWSAlgorithm algorithm,JWTClaimsSet claims) throws Exception {
        var jwt=new SignedJWT(new JWSHeader(algorithm),claims);jwt.sign(new RSASSASigner(key.getPrivate()));return jwt.serialize();
    }
    @Test void compatibleWithAllRsaAlgorithmsAuthCanChoose() throws Exception {
        var keys=key();var decoder=JwtConfig.decoder((RSAPublicKey)keys.getPublic());
        var claims=new JWTClaimsSet.Builder().subject("42").claim("email","test@example.com").issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(900))).build();
        for(var algorithm:new JWSAlgorithm[]{JWSAlgorithm.RS256,JWSAlgorithm.RS384,JWSAlgorithm.RS512})
            assertEquals("42",decoder.decode(sign(keys,algorithm,claims)).getSubject());
    }
    @Test void invalidSignatureExpiredMissingExpirySubjectAndUnsignedAreRejected() throws Exception {
        var keys=key();var decoder=JwtConfig.decoder((RSAPublicKey)keys.getPublic());
        var valid=new JWTClaimsSet.Builder().subject("42").expirationTime(Date.from(Instant.now().plusSeconds(900))).build();
        assertThrows(JwtException.class,()->decoder.decode(sign(key(),JWSAlgorithm.RS256,valid)));
        assertThrows(JwtException.class,()->decoder.decode(new PlainJWT(valid).serialize()));
        for(var claims:new JWTClaimsSet[]{new JWTClaimsSet.Builder().subject("42").build(),
                new JWTClaimsSet.Builder().expirationTime(Date.from(Instant.now().plusSeconds(900))).build(),
                new JWTClaimsSet.Builder().subject("42").expirationTime(Date.from(Instant.now().minusSeconds(5))).build()})
            assertThrows(JwtException.class,()->decoder.decode(sign(keys,JWSAlgorithm.RS256,claims)));
    }
}
