package com.pixelchiss.auth.service

import com.pixelchiss.auth.application.*
import com.pixelchiss.auth.domain.Pkce
import com.pixelchiss.auth.entity.LoginAttempt
import com.pixelchiss.auth.repository.LoginAttemptRepository
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class LoginFlowService(
    private val attempts: LoginAttemptRepository,
    private val google: DesktopIdentityProvider,
    private val users: UserService,
    private val jwt: AccessTokenIssuer,
    private val refresh: RefreshTokenService
) {
    data class Start(val authorizationUrl: String, val state: String, val expiresAt: Instant)
    data class Session(val accessToken: String, val refreshToken: String)
    private val random = SecureRandom()
    private fun secret(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun start(port: Int, challenge: String): Start {
        if (port !in 1024..65535 || !Pkce.validChallenge(challenge)) badRequest()
        val clientId = google.clientId() // Fails closed when Desktop credentials are not configured.
        val state=secret(); val nonce=secret(); val expires=Instant.now().plusSeconds(300)
        val redirect="http://127.0.0.1:$port/callback"
        attempts.save(LoginAttempt(Pkce.hash(state), "DESKTOP", challenge, nonce, redirect, expires))
        val params=linkedMapOf("client_id" to clientId, "redirect_uri" to redirect, "response_type" to "code",
            "scope" to "openid email profile", "state" to state, "nonce" to nonce,
            "code_challenge" to challenge, "code_challenge_method" to "S256")
        val query=params.entries.joinToString("&") { (k,v)->"$k="+URLEncoder.encode(v, Charsets.UTF_8) }
        return Start("https://accounts.google.com/o/oauth2/v2/auth?$query", state, expires)
    }

    fun exchangeDesktop(state: String, code: String, verifier: String): Session {
        if (code.isBlank() || code.length>4096) badRequest()
        val attempt=claim(state,verifier,"DESKTOP")
        // Claim commits before provider I/O: retries cannot redeem even after provider failure.
        val identity=google.exchange(code,verifier,attempt.redirectUri,attempt.nonce)
        val user=users.findByGoogleId(identity.subject) ?: users.createUser(identity.subject,identity.email,identity.name)
        return session(requireNotNull(user.id))
    }

    fun issueWebGrant(userId: Long, challenge: String): String {
        if (!Pkce.validChallenge(challenge)) badRequest()
        val code=secret()
        attempts.save(LoginAttempt(Pkce.hash(code),"WEB",challenge,"","",Instant.now().plusSeconds(60),userId))
        return code
    }

    fun exchangeWeb(code: String, verifier: String): Session = session(requireNotNull(claim(code,verifier,"WEB").userId))

    private fun claim(code: String, verifier: String, purpose: String): LoginAttempt {
        if (!Regex("[A-Za-z0-9_-]{43}").matches(code)) unauthorized()
        val attempt=attempts.findById(Pkce.hash(code)).orElse(null) ?: unauthorized()
        if (attempt.purpose!=purpose || !Pkce.matches(verifier,attempt.challenge)) unauthorized()
        if (attempts.consume(attempt.id,Instant.now())!=1) unauthorized()
        return attempt
    }

    private fun session(userId: Long): Session {
        val user=users.findById(userId) ?: unauthorized()
        return Session(jwt.generateAccessToken(userId,user.email),refresh.create(userId).token)
    }
    private fun badRequest(): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Parámetros OAuth/PKCE inválidos")
    private fun unauthorized(): Nothing = throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"Autorización inválida, usada o vencida")
}
