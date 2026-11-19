package com.pixelchiss.auth.security

import com.pixelchiss.auth.entity.RefreshToken
import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.service.LoginFlowService
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
    private val login=mock(LoginFlowService::class.java)
    private val handler = OAuth2LoginSuccessHandler(users, login)
    private val user = User(id = 42, googleId = "google-id", email = "stored@example.com", displayName = "Stored")
    private val refreshToken = RefreshToken(token = "refresh+/=", userId = 42, expiresAt = Instant.now().plusSeconds(60))

    @Test
    fun `existing user retains stored identity and redirects only a PKCE handoff code then invalidates session`() {
        `when`(users.findByGoogleId("google-id")).thenReturn(user)
        configureTokens()
        val request = requestWithClientSession()
        val session = request.getSession(false)!!
        val response = MockHttpServletResponse()
        handler.onAuthenticationSuccess(request, response, authentication())
        assertEquals(302, response.status)
        assertEquals(
            "http://127.0.0.1:12345/callback?code=one-use-code&state=client+state%2B",
            response.redirectedUrl
        )
        assertTrue((session as org.springframework.mock.web.MockHttpSession).isInvalid)
        verify(users, never()).createUser(anyString(), anyString(), anyString())
        verify(login).issueWebGrant(42,"a".repeat(43))
    }

    @Test
    fun `new user is created from Google attributes before issuing handoff code`() {
        `when`(users.findByGoogleId("google-id")).thenReturn(null)
        `when`(users.createUser("google-id", "google@example.com", "Google Name")).thenReturn(user)
        configureTokens()
        handler.onAuthenticationSuccess(requestWithClientSession(), MockHttpServletResponse(), authentication())
        val order = inOrder(users, login)
        order.verify(users).findByGoogleId("google-id")
        order.verify(users).createUser("google-id", "google@example.com", "Google Name")
        order.verify(login).issueWebGrant(42,"a".repeat(43))
    }

    @ParameterizedTest
    @ValueSource(strings = ["sub", "email", "name"])
    fun `missing Google attributes fail before any persistence or token issuance`(missing: String) {
        assertThrows(IllegalArgumentException::class.java) {
            handler.onAuthenticationSuccess(requestWithClientSession(), MockHttpServletResponse(), authentication(missing))
        }
        verifyNoInteractions(users, login)
    }

    @ParameterizedTest
    @ValueSource(strings = ["absent-session", "missing-state", "missing-port", "wrong-state-type", "wrong-port-type", "missing-challenge"])
    fun `invalid callback session never issues any credentials or grant`(case: String) {
        `when`(users.findByGoogleId("google-id")).thenReturn(user)
        configureTokens()
        val request = if (case == "absent-session") MockHttpServletRequest() else requestWithClientSession()
        val session = request.getSession(false)
        when (case) {
            "missing-state" -> session!!.removeAttribute(ClientLoginParamsFilter.STATE_ATTR)
            "missing-port" -> session!!.removeAttribute(ClientLoginParamsFilter.PORT_ATTR)
            "missing-challenge" -> session!!.removeAttribute(ClientLoginParamsFilter.CHALLENGE_ATTR)
            "wrong-state-type" -> session!!.setAttribute(ClientLoginParamsFilter.STATE_ATTR, 42)
            "wrong-port-type" -> session!!.setAttribute(ClientLoginParamsFilter.PORT_ATTR, "12345")
        }
        val response = MockHttpServletResponse()
        handler.onAuthenticationSuccess(request, response, authentication())
        assertEquals(400,response.status)
        verifyNoInteractions(login)
        verify(users,never()).findByGoogleId(anyString())
        assertNull(response.redirectedUrl)
        if (session != null) assertTrue((session as org.springframework.mock.web.MockHttpSession).isInvalid)
    }

    private fun configureTokens() {
        `when`(login.issueWebGrant(42,"a".repeat(43))).thenReturn("one-use-code")
    }

    private fun requestWithClientSession() = MockHttpServletRequest().apply {
        requireNotNull(getSession(true)).apply {
            setAttribute(ClientLoginParamsFilter.STATE_ATTR, "client state+")
            setAttribute(ClientLoginParamsFilter.PORT_ATTR, 12345)
            setAttribute(ClientLoginParamsFilter.CHALLENGE_ATTR,"a".repeat(43))
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
