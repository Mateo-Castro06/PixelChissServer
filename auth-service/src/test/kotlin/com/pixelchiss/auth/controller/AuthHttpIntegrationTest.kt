package com.pixelchiss.auth.controller

import com.pixelchiss.auth.entity.RefreshToken
import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.repository.RefreshTokenRepository
import com.pixelchiss.auth.repository.UserRepository
import com.pixelchiss.auth.security.ClientLoginParamsFilter
import com.pixelchiss.auth.security.JwtService
import com.pixelchiss.auth.support.AuthIntegrationTest
import com.pixelchiss.auth.support.TestKeys
import io.jsonwebtoken.Jwts
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.endpoint.DefaultOAuth2TokenRequestParametersConverter
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.util.Date
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@AutoConfigureMockMvc
@Transactional
class AuthHttpIntegrationTest : AuthIntegrationTest() {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var refreshTokens: RefreshTokenRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var registrations: ClientRegistrationRepository

    @Test
    fun `refresh returns only accessToken and keeps persisted refresh token reusable`() {
        val user = createUser()
        val refresh = createRefresh(user.id!!)
        repeat(2) {
            val result = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"${refresh.token}"}"""))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.*", hasSize<Any>(1)))
                .andExpect(jsonPath("$.accessToken").isString)
                .andReturn()
            val token = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(result.response.contentAsString)["accessToken"].asString()
            val claims = Jwts.parser().verifyWith(TestKeys.publicKey).build().parseSignedClaims(token).payload
            assertEquals(user.id.toString(), claims.subject)
            assertEquals(user.email, claims["email"])
            assertEquals(900_000L, claims.expiration.time - claims.issuedAt.time)
        }
        entityManager.flush()
        entityManager.clear()
        val stored = refreshTokens.findByToken(refresh.token).orElseThrow()
        assertFalse(stored.revoked)
        assertEquals(refresh.expiresAt, stored.expiresAt)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "expired", "revoked", "missing-user"])
    fun `refresh errors retain HTTP status and reasons`(case: String) {
        val token = when (case) {
            "missing" -> "not-stored"
            "missing-user" -> createRefresh(Long.MAX_VALUE).token
            else -> createRefresh(createUser().id!!,
                expiresAt = if (case == "expired") Instant.now().minusSeconds(60) else Instant.now().plusSeconds(60),
                revoked = case == "revoked").token
        }
        val result = mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("""{"refreshToken":"$token"}"""))
            .andExpect(status().isUnauthorized).andReturn()
        assertEquals(if (case == "missing-user") "Usuario no encontrado" else "Refresh token inválido o expirado",
            (result.resolvedException as ResponseStatusException).reason)
    }

    @Test
    fun `logout is public idempotent and revokes only the supplied refresh token`() {
        val user = createUser()
        val token = createRefresh(user.id!!)
        val other = createRefresh(user.id!!)
        for (value in listOf(token.token, token.token, "missing")) {
            mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$value"}"""))
                .andExpect(status().isOk)
                .andExpect(content().json("""{"message":"Sesión cerrada correctamente"}"""))
                .andExpect(jsonPath("$.*", hasSize<Any>(1)))
        }
        entityManager.flush()
        entityManager.clear()
        assertTrue(refreshTokens.findByToken(token.token).orElseThrow().revoked)
        assertFalse(refreshTokens.findByToken(other.token).orElseThrow().revoked)
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
            .content("""{"refreshToken":"${token.token}"}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(user)))
            .andExpect(status().isOk)
    }

    @Test
    fun `profile retains all fields and test endpoint retains identity`() {
        val user = createUser()
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(user)))
            .andExpect(status().isOk)
            .andExpect(content().json(profileJson(user)))
            .andExpect(jsonPath("$.*", hasSize<Any>(4)))
        mvc.perform(get("/api/test/protected").header("Authorization", bearer(user)))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"message":"JWT válido","user":"${user.id}"}"""))
    }

    @Test
    fun `display name is trimmed validated saved and reflected in profile`() {
        val user = createUser()
        mvc.perform(put("/api/auth/me/display-name").header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"  Player_123  "}"""))
            .andExpect(status().isOk)
            .andExpect(content().json(profileJson(user, "Player_123")))
            .andExpect(jsonPath("$.*", hasSize<Any>(4)))
        entityManager.flush()
        entityManager.clear()
        assertEquals("Player_123", users.findById(user.id!!).orElseThrow().displayName)
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(user)))
            .andExpect(content().json(profileJson(user, "Player_123")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "abcdefghijklmnopqrstuvwx", "___", "123"])
    fun `display name boundaries and allowed characters are unchanged`(name: String) {
        val user = createUser()
        mvc.perform(put("/api/auth/me/display-name").header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"$name"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.displayName").value(name))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "ab", "abcdefghijklmnopqrstuvwxy", "Player One", "niño", "a-b", "a.b"])
    fun `invalid display name returns existing error without updating user`(name: String) {
        val user = createUser()
        val result = mvc.perform(put("/api/auth/me/display-name").header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"$name"}"""))
            .andExpect(status().isBadRequest).andReturn()
        assertEquals("El nombre debe tener entre 3 y 24 caracteres: letras, números o guion bajo",
            (result.resolvedException as ResponseStatusException).reason)
        assertEquals("Original", users.findById(user.id!!).orElseThrow().displayName)
    }

    @Test
    fun `profile errors retain subject validation before name validation before lookup`() {
        val invalidSubject = "Bearer ${signedToken("not-a-number") }"
        val result = mvc.perform(put("/api/auth/me/display-name").header("Authorization", invalidSubject)
            .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"!"}"""))
            .andExpect(status().isUnauthorized).andReturn()
        assertEquals("ID de usuario inválido", (result.resolvedException as ResponseStatusException).reason)
        val missingUser = "Bearer ${signedToken(Long.MAX_VALUE.toString())}"
        mvc.perform(put("/api/auth/me/display-name").header("Authorization", missingUser)
            .contentType(MediaType.APPLICATION_JSON).content("""{"displayName":"!"}"""))
            .andExpect(status().isBadRequest)
        for (path in listOf("/api/auth/me", "/api/auth/me/display-name")) {
            val request = if (path.endsWith("display-name")) put(path).contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Valid"}""") else get(path)
            val missing = mvc.perform(request.header("Authorization", missingUser))
                .andExpect(status().isNotFound).andReturn()
            assertEquals("Usuario no encontrado", (missing.resolvedException as ResponseStatusException).reason)
        }
        val invalid = mvc.perform(get("/api/auth/me").header("Authorization", invalidSubject))
            .andExpect(status().isUnauthorized).andReturn()
        assertEquals("ID de usuario inválido", (invalid.resolvedException as ResponseStatusException).reason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["missing", "malformed", "expired", "wrong-signature"])
    fun `protected routes reject absent malformed expired and untrusted JWTs`(case: String) {
        val value = when (case) {
            "missing" -> null
            "malformed" -> "Bearer invalid"
            "expired" -> "Bearer ${signedToken("42", -300)}"
            else -> {
                val otherKey = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                "Bearer ${Jwts.builder().subject("42").expiration(Date.from(Instant.now().plusSeconds(900)))
                    .signWith(otherKey.private).compact()}"
            }
        }
        for (path in listOf("/api/auth/me", "/api/test/protected", "/api/auth/me/display-name")) {
            val request = if (path.endsWith("display-name")) put(path).contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Valid"}""") else get(path)
            value?.let { request.header("Authorization", it) }
            mvc.perform(request.accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `malformed bodies and missing required JSON properties remain bad requests`() {
        for (path in listOf("/api/auth/refresh", "/api/auth/logout")) {
            for (body in listOf("{}", "{", """{"refreshToken":null}""")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest)
            }
        }
        val user = createUser()
        mvc.perform(put("/api/auth/me/display-name").header("Authorization", bearer(user))
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `OAuth start preserves Google parameters client session independent state and current PKCE defaults`() {
        val clientState = "0123456789abcdef"
        val result = mvc.perform(get("/oauth2/authorization/google")
            .param("client_state", clientState).param("client_port", "12345"))
            .andExpect(status().isFound).andReturn()
        val redirect = UriComponentsBuilder.fromUriString(result.response.redirectedUrl!!).build()
        assertEquals("accounts.google.com", redirect.host)
        assertEquals("/o/oauth2/v2/auth", redirect.path)
        val params = redirect.queryParams.mapValues { (_, values) ->
            values.map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
        }
        assertEquals("code", params["response_type"]?.single())
        assertEquals("test-client", params["client_id"]?.single())
        assertEquals(setOf("openid", "profile", "email"), params["scope"]!!.single().split(" ").toSet())
        assertEquals("http://localhost:8081/login/oauth2/code/google", params["redirect_uri"]?.single())
        assertNotEquals(clientState, params["state"]?.single())
        assertFalse(params["state"]?.single().isNullOrBlank())
        // Spring Security 7.1.1 enables proof keys by default, including confidential clients.
        assertTrue(requireNotNull(registrations.findByRegistrationId("google")).clientSettings.isRequireProofKey)
        assertEquals("S256", params["code_challenge_method"]?.single())
        assertFalse(params["code_challenge"]?.single().isNullOrBlank())
        val session = result.request.getSession(false) as MockHttpSession
        assertEquals(clientState, session.getAttribute(ClientLoginParamsFilter.STATE_ATTR))
        assertEquals(12345, session.getAttribute(ClientLoginParamsFilter.PORT_ATTR))
        val repository = HttpSessionOAuth2AuthorizationRequestRepository()
        val savedRequest = org.springframework.mock.web.MockHttpServletRequest().apply {
            setSession(session)
            setParameter("state", requireNotNull(params["state"]?.single()))
        }
        val authorization = requireNotNull(repository.loadAuthorizationRequest(savedRequest))
        val verifier = authorization.getAttribute<String>("code_verifier")!!
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        assertEquals(expectedChallenge, params["code_challenge"]?.single())
        assertFalse(params.containsKey("code_verifier"))
        val authorizationResponse = OAuth2AuthorizationResponse.success("test-code")
            .redirectUri(requireNotNull(authorization.redirectUri)).state(requireNotNull(authorization.state)).build()
        val grant = OAuth2AuthorizationCodeGrantRequest(
            requireNotNull(registrations.findByRegistrationId("google")),
            OAuth2AuthorizationExchange(authorization, authorizationResponse)
        )
        val tokenParameters = DefaultOAuth2TokenRequestParametersConverter<OAuth2AuthorizationCodeGrantRequest>()
            .convert(grant)
        assertEquals(verifier, tokenParameters.getFirst("code_verifier"))
        assertEquals("test-code", tokenParameters.getFirst("code"))
        assertEquals("authorization_code", tokenParameters.getFirst("grant_type"))
        savedRequest.setParameter("state", "wrong-state")
        assertNull(repository.loadAuthorizationRequest(savedRequest))
        mvc.perform(get("/login/oauth2/code/google").session(session).param("state", "wrong-state").param("code", "unused"))
            .andExpect(status().isFound).andExpect(redirectedUrl("/login?error"))
    }

    @Test
    fun `OAuth start rejects missing client parameters and public health remains accessible`() {
        mvc.perform(get("/oauth2/authorization/google")).andExpect(status().isBadRequest)
        mvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    @Test
    fun `invalid bearer headers are still rejected even on public token endpoints`() {
        for (path in listOf("/api/auth/refresh", "/api/auth/logout")) {
            mvc.perform(post(path).header("Authorization", "Bearer invalid")
                .contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"missing"}"""))
                .andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `OpenAPI retains paths methods and DTO property names`() {
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['200']").exists())
            .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['200']").exists())
            .andExpect(jsonPath("$.paths['/api/auth/me'].get.responses['200']").exists())
            .andExpect(jsonPath("$.paths['/api/auth/me/display-name'].put.responses['200']").exists())
            .andExpect(jsonPath("$.components.schemas.TokenResponse.properties.accessToken").exists())
            .andExpect(jsonPath("$.components.schemas.RefreshTokenRequest.properties.refreshToken").exists())
            .andExpect(jsonPath("$.components.schemas.LogoutRequest.properties.refreshToken").exists())
            .andExpect(jsonPath("$.components.schemas.UpdateDisplayNameRequest.properties.displayName").exists())
            .andExpect(jsonPath("$.components.schemas.UserProfileResponse.properties.createdAt").exists())
    }

    @Test
    fun `PostgreSQL preserves user identity lookup and refresh token association`() {
        val user = createUser()
        val token = createRefresh(user.id!!)
        entityManager.flush()
        entityManager.clear()
        val loaded = users.findByGoogleId(user.googleId).orElseThrow()
        assertEquals(user.id, loaded.id)
        assertEquals(user.id, users.findByEmail(user.email).orElseThrow().id)
        assertEquals(user.createdAt, loaded.createdAt)
        val loadedToken = refreshTokens.findByToken(token.token).orElseThrow()
        assertEquals(user.id, loadedToken.userId)
        assertEquals(token.expiresAt, loadedToken.expiresAt)
        assertFalse(loadedToken.revoked)
    }

    private fun createUser(): User = users.save(User(
        googleId = java.util.UUID.randomUUID().toString(), email = "${java.util.UUID.randomUUID()}@example.com",
        displayName = "Original", createdAt = Instant.parse("2024-01-02T03:04:05Z")
    ))

    private fun createRefresh(userId: Long, expiresAt: Instant = Instant.parse("2099-01-01T00:00:00Z"),
                              revoked: Boolean = false): RefreshToken = refreshTokens.save(RefreshToken(
        token = java.util.UUID.randomUUID().toString(), userId = userId, expiresAt = expiresAt, revoked = revoked
    ))

    private fun bearer(user: User) = "Bearer ${jwtService.generateAccessToken(user.id!!, user.email)}"

    private fun signedToken(subject: String, remainingSeconds: Long = 900) = Jwts.builder().subject(subject)
        .issuedAt(Date.from(Instant.now().minusSeconds(600)))
        .expiration(Date.from(Instant.now().plusSeconds(remainingSeconds)))
        .signWith(TestKeys.pair.private).compact()

    private fun profileJson(user: User, name: String = user.displayName) =
        """{"id":${user.id},"email":"${user.email}","displayName":"$name","createdAt":"${user.createdAt}"}"""
}
