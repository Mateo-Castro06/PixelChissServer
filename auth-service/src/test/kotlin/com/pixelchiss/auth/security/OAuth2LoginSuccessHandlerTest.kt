package com.pixelchiss.auth.security

import com.pixelchiss.auth.entity.RefreshToken
import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.service.RefreshTokenService
import com.pixelchiss.auth.service.UserService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import java.time.Instant

class OAuth2LoginSuccessHandlerTest {
    private val users = mock(UserService::class.java)
    private val jwt = mock(JwtService::class.java)
    private val refreshTokens = mock(RefreshTokenService::class.java)
    private val handler = OAuth2LoginSuccessHandler(users, jwt, refreshTokens)
    private val user = User(id = 42, googleId = "google-id", email = "stored@example.com", displayName = "Stored")
    private val refreshToken = RefreshToken(token = "refresh+/=", userId = 42, expiresAt = Instant.now().plusSeconds(60))

    @Test
    fun `existing user retains stored identity and redirects with encoded tokens then invalidates session`() {
        `when`(users.findByGoogleId("google-id")).thenReturn(user)
        configureTokens()
        val request = requestWithClientSession()
        val session = request.getSession(false)!!
        val response = MockHttpServletResponse()
        handler.onAuthenticationSuccess(request, response, authentication())
        assertEquals(302, response.status)
        assertEquals(
            "http://127.0.0.1:12345/callback?accessToken=access%2B%2F%3D&refreshToken=refresh%2B%2F%3D&state=client+state%2B",
            response.redirectedUrl
        )
        assertTrue((session as org.springframework.mock.web.MockHttpSession).isInvalid)
        verify(users, never()).createUser(anyString(), anyString(), anyString())
        verify(jwt).generateAccessToken(42, "stored@example.com")
        verify(refreshTokens).create(42)
    }

    @Test
    fun `new user is created from Google attributes before issuing tokens`() {
        `when`(users.findByGoogleId("google-id")).thenReturn(null)
        `when`(users.createUser("google-id", "google@example.com", "Google Name")).thenReturn(user)
        configureTokens()
        handler.onAuthenticationSuccess(requestWithClientSession(), MockHttpServletResponse(), authentication())
        val order = inOrder(users, jwt, refreshTokens)
        order.verify(users).findByGoogleId("google-id")
        order.verify(users).createUser("google-id", "google@example.com", "Google Name")
        order.verify(jwt).generateAccessToken(42, "stored@example.com")
        order.verify(refreshTokens).create(42)
    }

    @ParameterizedTest
    @ValueSource(strings = ["sub", "email", "name"])
    fun `missing Google attributes fail before any persistence or token issuance`(missing: String) {
        assertThrows(IllegalArgumentException::class.java) {
            handler.onAuthenticationSuccess(requestWithClientSession(), MockHttpServletResponse(), authentication(missing))
        }
        verifyNoInteractions(users, jwt, refreshTokens)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent-session", "missing-state", "missing-port", "wrong-state-type", "wrong-port-type"])
    fun `invalid callback session retains existing failure and issuance order`(case: String) {
        `when`(users.findByGoogleId("google-id")).thenReturn(user)
        configureTokens()
        val request = if (case == "absent-session") MockHttpServletRequest() else requestWithClientSession()
        val session = request.getSession(false)
        when (case) {
            "missing-state" -> session!!.removeAttribute(ClientLoginParamsFilter.STATE_ATTR)
            "missing-port" -> session!!.removeAttribute(ClientLoginParamsFilter.PORT_ATTR)
            "wrong-state-type" -> session!!.setAttribute(ClientLoginParamsFilter.STATE_ATTR, 42)
            "wrong-port-type" -> session!!.setAttribute(ClientLoginParamsFilter.PORT_ATTR, "12345")
        }
        val response = MockHttpServletResponse()
        assertThrows(IllegalArgumentException::class.java) {
            handler.onAuthenticationSuccess(request, response, authentication())
        }
        verify(jwt).generateAccessToken(42, "stored@example.com")
        verify(refreshTokens).create(42)
        assertNull(response.redirectedUrl)
        if (session != null) assertFalse((session as org.springframework.mock.web.MockHttpSession).isInvalid)
    }

    private fun configureTokens() {
        `when`(jwt.generateAccessToken(42, "stored@example.com")).thenReturn("access+/=")
        `when`(refreshTokens.create(42)).thenReturn(refreshToken)
    }

    private fun requestWithClientSession() = MockHttpServletRequest().apply {
        requireNotNull(getSession(true)).apply {
            setAttribute(ClientLoginParamsFilter.STATE_ATTR, "client state+")
            setAttribute(ClientLoginParamsFilter.PORT_ATTR, 12345)
        }
    }

    private fun authentication(missing: String? = null): Authentication {
        val attributes = mutableMapOf<String, Any>(
            "sub" to "google-id", "email" to "google@example.com", "name" to "Google Name", "principal" to "google-id"
        )
        missing?.let { attributes.remove(it) }
        val principal = DefaultOAuth2User(emptyList(), attributes, "principal")
        return UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }
}
