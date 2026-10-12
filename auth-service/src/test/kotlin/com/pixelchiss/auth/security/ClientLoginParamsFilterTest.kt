package com.pixelchiss.auth.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ClientLoginParamsFilterTest {
    private val filter = ClientLoginParamsFilter()
    private val validState = "aB09_-aB09_-aB09_"

    @ParameterizedTest
    @ValueSource(ints = [1024, 65535])
    fun `valid parameters are stored in session and continue the chain`(port: Int) {
        val request = request(validState, port.toString())
        val session = requireNotNull(request.getSession(true))
        session.setAttribute("existing", "preserved")
        var continued = false
        filter.doFilter(request, MockHttpServletResponse()) { _, _ -> continued = true }
        assertTrue(continued)
        assertEquals(validState, session.getAttribute(ClientLoginParamsFilter.STATE_ATTR))
        assertEquals(port, session.getAttribute(ClientLoginParamsFilter.PORT_ATTR))
        assertEquals("preserved", session.getAttribute("existing"))
    }

    @ParameterizedTest
    @ValueSource(ints = [16, 128])
    fun `state length boundaries are accepted`(length: Int) {
        val request = request("a".repeat(length), "1234")
        var continued = false
        filter.doFilter(request, MockHttpServletResponse()) { _, _ -> continued = true }
        assertTrue(continued)
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["short", "aaaaaaaaaaaaaaa", "invalid.state____", "espacio invalido_", "áaaaaaaaaaaaaaaa"])
    fun `invalid state returns the existing plain text error without a session`(state: String?) {
        assertRejected(request(state, "1234"))
    }

    @Test
    fun `state longer than 128 characters is rejected`() {
        assertRejected(request("a".repeat(129), "1234"))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = ["1023", "65536", "0", "-1", "abc", "2147483648", "1234.5"])
    fun `invalid port returns the existing error`(port: String?) {
        assertRejected(request(validState, port))
    }

    @Test
    fun `other paths bypass the filter`() {
        val request = MockHttpServletRequest("GET", "/login/oauth2/code/google")
        var continued = false
        filter.doFilter(request, MockHttpServletResponse()) { _, _ -> continued = true }
        assertTrue(continued)
        assertNull(request.getSession(false))
    }

    private fun request(state: String?, port: String?) =
        MockHttpServletRequest("GET", "/oauth2/authorization/google").apply {
            state?.let { addParameter("client_state", it) }
            port?.let { addParameter("client_port", it) }
        }

    private fun assertRejected(request: MockHttpServletRequest) {
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> fail<Unit>("The chain must not continue") }
        assertEquals(400, response.status)
        assertEquals("text/plain;charset=UTF-8", response.contentType)
        assertEquals("Faltan client_state / client_port válidos. Inicia el login desde el juego.", response.contentAsString)
        assertNull(request.getSession(false))
    }
}
