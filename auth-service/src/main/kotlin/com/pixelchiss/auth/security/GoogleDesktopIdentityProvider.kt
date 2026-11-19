package com.pixelchiss.auth.security

import com.pixelchiss.auth.application.*
import java.time.Instant
import java.security.MessageDigest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException

@Component
class GoogleDesktopIdentityProvider(
    @Value("\${GOOGLE_DESKTOP_CLIENT_ID:}") private val desktopId: String,
    @Value("\${GOOGLE_DESKTOP_CLIENT_SECRET:}") private val desktopSecret: String
) : DesktopIdentityProvider {
    private val decoder=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build()
    private val http=RestClient.builder().requestFactory(SimpleClientHttpRequestFactory().apply {
        setConnectTimeout(5000);setReadTimeout(5000)
    }).build()
    override fun clientId(): String {
        if (desktopId.isBlank()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"OAuth Desktop no configurado por el anfitrión")
        return desktopId
    }
    override fun exchange(code: String, verifier: String, redirectUri: String, nonce: String): GoogleIdentity {
        val params=LinkedMultiValueMap<String,String>()
        params.add("client_id",clientId());params.add("code",code);params.add("code_verifier",verifier)
        params.add("redirect_uri",redirectUri);params.add("grant_type","authorization_code")
        if (desktopSecret.isNotBlank()) params.add("client_secret",desktopSecret)
        try {
            val result=http.post().uri("https://oauth2.googleapis.com/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params).retrieve().body(Map::class.java) ?: error("Missing response")
            return decodeIdToken(result["id_token"] as? String ?: error("Missing ID token"),nonce)
        } catch (_: Exception) {
            // Never propagate Google's response body, codes, verifier or tokens into errors/logs.
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"Google no pudo validar la autorización")
        }
    }
    internal fun decodeIdToken(raw: String, nonce: String, verifier: org.springframework.security.oauth2.jwt.JwtDecoder = decoder): GoogleIdentity =
        verifiedIdentity(verifier.decode(raw),desktopId,nonce)
    companion object {
        fun verifiedIdentity(token: org.springframework.security.oauth2.jwt.Jwt, clientId: String, nonce: String): GoogleIdentity {
            require(token.getClaimAsString("iss") in setOf("https://accounts.google.com","accounts.google.com"))
            require(token.audience?.contains(clientId)==true)
            require(token.getClaimAsString("azp")==null || token.getClaimAsString("azp")==clientId)
            require(token.expiresAt?.isAfter(Instant.now())==true)
            require(token.getClaimAsString("nonce")?.let { MessageDigest.isEqual(it.toByteArray(),nonce.toByteArray()) }==true)
            require(token.getClaimAsBoolean("email_verified")==true)
            val subject=token.subject; val email=token.getClaimAsString("email"); val name=token.getClaimAsString("name")
            require(!subject.isNullOrBlank() && !email.isNullOrBlank() && !name.isNullOrBlank())
            return GoogleIdentity(subject,email,name)
        }
    }
}
