package com.pixelchiss.auth.security

import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.service.UserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class OAuth2LoginSuccessHandler(
    private val userService: UserService,
    private val login: com.pixelchiss.auth.service.LoginFlowService
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val session=request.getSession(false)
        val state=session?.getAttribute(ClientLoginParamsFilter.STATE_ATTR) as? String
        val port=session?.getAttribute(ClientLoginParamsFilter.PORT_ATTR) as? Int
        val challenge=session?.getAttribute(ClientLoginParamsFilter.CHALLENGE_ATTR) as? String
        if (state==null || port==null || port !in 1024..65535 || challenge==null || !com.pixelchiss.auth.domain.Pkce.validChallenge(challenge)) {
            session?.invalidate()
            response.sendError(400,"Actualiza el cliente para el retorno OAuth con PKCE")
            return
        }
        val user=findOrCreateUser(authentication.principal as OAuth2User)
        val code=login.issueWebGrant(requireNotNull(user.id),challenge)
        session.invalidate()
        response.setHeader("Cache-Control","no-store")
        response.setHeader("Referrer-Policy","no-referrer")
        response.sendRedirect("http://127.0.0.1:$port/callback?code="+encodeQueryValue(code)+"&state="+encodeQueryValue(state))
    }

    private fun findOrCreateUser(oauth2User: OAuth2User): User {
        val googleId = oauth2User.getAttribute<String>("sub")
        val email = oauth2User.getAttribute<String>("email")
        val displayName = oauth2User.getAttribute<String>("name")

        requireNotNull(googleId)
        requireNotNull(email)
        requireNotNull(displayName)

        return userService.findByGoogleId(googleId) ?: userService.createUser(
            googleId = googleId,
            email = email,
            displayName = displayName
        )
    }

    private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
