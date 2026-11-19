package com.pixelchiss.auth.security

import com.pixelchiss.auth.support.TestKeys
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.*
import java.time.Instant
import java.util.Date

class GoogleDesktopIdentityTest {
    val provider=GoogleDesktopIdentityProvider("desktop-id","")
    val decoder=NimbusJwtDecoder.withPublicKey(TestKeys.publicKey).build()
    fun token(overrides:Map<String,Any> = emptyMap()):String {
        val claims=mutableMapOf<String,Any>("iss" to "https://accounts.google.com","aud" to "desktop-id",
            "sub" to "google-identity","email" to "verified@example.invalid","name" to "Player",
            "email_verified" to true,"nonce" to "nonce")
        claims.putAll(overrides)
        return Jwts.builder().claims(claims).issuedAt(Date.from(Instant.now().minusSeconds(1)))
            .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(TestKeys.pair.private,Jwts.SIG.RS256).compact()
    }
    @Test fun `signed identity requires audience issuer nonce verified email and expiry`() {
        assertEquals("google-identity",provider.decodeIdToken(token(),"nonce",decoder).subject)
        assertEquals("google-identity",provider.decodeIdToken(token(mapOf("iss" to "accounts.google.com")),"nonce",decoder).subject)
        for(change in listOf(mapOf("aud" to "web-id"),mapOf("iss" to "https://evil.invalid"),mapOf("nonce" to "other"),mapOf("email_verified" to false),mapOf("azp" to "other")))
            assertThrows(IllegalArgumentException::class.java){provider.decodeIdToken(token(change),"nonce",decoder)}
        assertThrows(JwtException::class.java){provider.decodeIdToken(token().dropLast(8)+"invalid","nonce",decoder)}
        val expired=Jwt.withTokenValue("verified-fixture").header("alg","RS256").claims{it.putAll(mapOf("iss" to "https://accounts.google.com","aud" to listOf("desktop-id"),"nonce" to "nonce"))}
            .expiresAt(Instant.now().minusSeconds(1)).build()
        assertThrows(IllegalArgumentException::class.java){GoogleDesktopIdentityProvider.verifiedIdentity(expired,"desktop-id","nonce")}
    }
    @Test fun `PKCE S256 matches RFC7636 vector and rejects invalid verifier alphabet`() {
        val verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge="E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertTrue(com.pixelchiss.auth.domain.Pkce.matches(verifier,challenge))
        assertFalse(com.pixelchiss.auth.domain.Pkce.matches("!".repeat(43),challenge))
        assertFalse(com.pixelchiss.auth.domain.Pkce.matches(verifier,"a".repeat(43)))
    }
    @Test fun `Google exchange sends PKCE and optional host secret in form body and verifies signed identity`() {
        val secured=GoogleDesktopIdentityProvider("desktop-id","server-only")
        val builder=org.springframework.web.client.RestClient.builder()
        val server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build()
        org.springframework.test.util.ReflectionTestUtils.setField(secured,"http",builder.build())
        org.springframework.test.util.ReflectionTestUtils.setField(secured,"decoder",decoder)
        val form=org.springframework.util.LinkedMultiValueMap<String,String>()
        form.add("client_id","desktop-id");form.add("client_secret","server-only");form.add("code","code+/")
        form.add("code_verifier","v".repeat(43));form.add("redirect_uri","http://127.0.0.1:45678/callback")
        form.add("grant_type","authorization_code")
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
            .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().formData(form))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                """{"id_token":"${token()}","access_token":"google-token-must-not-leak"}""",org.springframework.http.MediaType.APPLICATION_JSON))
        val identity=secured.exchange("code+/","v".repeat(43),"http://127.0.0.1:45678/callback","nonce")
        assertEquals("google-identity",identity.subject)
        assertFalse(identity.toString().contains("google-token-must-not-leak"));server.verify()
    }
    @Test fun `provider errors never propagate response bodies secrets or exception causes`() {
        val builder=org.springframework.web.client.RestClient.builder()
        val server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build()
        org.springframework.test.util.ReflectionTestUtils.setField(provider,"http",builder.build())
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://oauth2.googleapis.com/token"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest()
                .body("secret-provider-body-must-not-leak"))
        val error=assertThrows(org.springframework.web.server.ResponseStatusException::class.java){
            provider.exchange("code","v".repeat(43),"http://127.0.0.1:45678/callback","nonce")
        }
        assertEquals(401,error.statusCode.value());assertNull(error.cause)
        assertFalse(error.toString().contains("secret-provider-body-must-not-leak"));server.verify()
    }
    @Test fun `absent configuration fails closed`() {
        assertThrows(org.springframework.web.server.ResponseStatusException::class.java){GoogleDesktopIdentityProvider("","").clientId()}
    }
}
