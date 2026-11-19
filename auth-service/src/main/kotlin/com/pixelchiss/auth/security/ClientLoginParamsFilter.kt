package com.pixelchiss.auth.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

class ClientLoginParamsFilter : OncePerRequestFilter() {

    companion object {
        const val STATE_ATTR = "CLIENT_LOGIN_STATE"
        const val CHALLENGE_ATTR = "CLIENT_LOGIN_CHALLENGE"
        const val PORT_ATTR = "CLIENT_LOGIN_PORT"
        private val STATE_REGEX = Regex("^[A-Za-z0-9_-]{16,128}$")
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/oauth2/authorization/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val state = request.getParameter("client_state")
        val port = request.getParameter("client_port")?.toIntOrNull()
        val challenge=request.getParameter("client_challenge")

        if (state == null ||
            !STATE_REGEX.matches(state) ||
            port == null ||
            port !in 1024..65535
        ) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.contentType = "text/plain;charset=UTF-8"
            response.writer.write(
                "Faltan client_state / client_port válidos. Inicia el login desde el juego."
            )
            return
        }

        val session = request.getSession(true)

        session.setAttribute(STATE_ATTR, state)
        session.setAttribute(PORT_ATTR, port)
        if (challenge != null) {
            if (!com.pixelchiss.auth.domain.Pkce.validChallenge(challenge)) { response.sendError(400);return }
            session.setAttribute(CHALLENGE_ATTR,challenge)
        } else session.removeAttribute(CHALLENGE_ATTR)

        filterChain.doFilter(request, response)
    }
}