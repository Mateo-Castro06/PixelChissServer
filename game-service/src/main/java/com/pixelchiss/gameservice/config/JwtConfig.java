package com.pixelchiss.gameservice.config;

import com.nimbusds.jose.JWSAlgorithm;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtConfig {
    @Bean public JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.public-key-location}") Resource pem) throws IOException {
        try(var input=pem.getInputStream()) {return decoder(RsaKeyConverters.x509().convert(input));}
    }
    public static NimbusJwtDecoder decoder(RSAPublicKey key) {
        // Auth's JJWT signWith(key) picks RS256/384/512 according to RSA key size.
        var allowed=Set.of(JWSAlgorithm.RS256,JWSAlgorithm.RS384,JWSAlgorithm.RS512);
        var decoder=NimbusJwtDecoder.withPublicKey(key).jwtProcessorCustomizer(p->p.setJWSKeySelector(
                (header,context)->allowed.contains(header.getAlgorithm())?List.of(key):List.of())).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtClaimValidator<String>("sub",s->s!=null&&!s.isBlank()),
                new JwtClaimValidator<Instant>("exp",Objects::nonNull)));
        return decoder;
    }
}
