package com.pixelchiss.auth.security

import com.pixelchiss.auth.service.RefreshTokenService
import com.pixelchiss.auth.service.UserService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component

@Component
class OAuth2LoginSuccessHandler(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService
) : AuthenticationSuccessHandler {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oauth2User = authentication.principal as OAuth2User

        val googleId = oauth2User.getAttribute<String>("sub")
        val email = oauth2User.getAttribute<String>("email")
        val displayName = oauth2User.getAttribute<String>("name")

        requireNotNull(googleId)
        requireNotNull(email)
        requireNotNull(displayName)

        val existingUser = userService.findByGoogleId(googleId)

        val user = existingUser ?: userService.createUser(
            googleId = googleId,
            email = email,
            displayName = displayName
        )

        val accessToken = jwtService.generateAccessToken(
            userId = user.id!!,
            email = user.email
        )

        val refreshToken = refreshTokenService.create(
            userId = user.id!!
        )

        response.contentType = "application/json"
        response.writer.write(
            """
            {
                "accessToken": "$accessToken",
                "refreshToken": "${refreshToken.token}"
            }
            """.trimIndent()
        )
    }
}
