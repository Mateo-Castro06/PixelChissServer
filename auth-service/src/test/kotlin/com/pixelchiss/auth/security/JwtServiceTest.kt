package com.pixelchiss.auth.security

import com.pixelchiss.auth.support.TestKeys
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.nio.file.NoSuchFileException

class JwtServiceTest {
    @Test
    fun `tokens retain signature claims lifetime and converter identity`() {
        val token = JwtService(TestKeys.privateKeyPath.toString()).generateAccessToken(42, "player@example.com")
        val parsed = Jwts.parser().verifyWith(TestKeys.publicKey).build().parseSignedClaims(token)
        val claims = parsed.payload
        assertEquals("RS256", parsed.header.algorithm)
        assertEquals(setOf("sub", "email", "iat", "exp"), claims.keys)
        assertEquals("42", claims.subject)
        assertEquals("player@example.com", claims["email"])
        assertEquals(900_000L, claims.expiration.time - claims.issuedAt.time)
        val jwt = NimbusJwtDecoder.withPublicKey(TestKeys.publicKey).build().decode(token)
        val authentication = JwtAuthenticationConverter().convert(jwt)
        assertEquals("42", authentication.name)
        assertTrue(authentication.authorities.isEmpty())
        assertSame(jwt, authentication.principal)
    }

    @Test
    fun `private key loading remains lazy and invalid paths fail on issuance`() {
        val service = JwtService(TestKeys.privateKeyPath.resolveSibling("missing.pem").toString())
        assertThrows(NoSuchFileException::class.java) { service.generateAccessToken(42, "player@example.com") }
    }
}
