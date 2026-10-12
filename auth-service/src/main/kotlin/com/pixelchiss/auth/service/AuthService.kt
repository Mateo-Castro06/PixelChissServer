package com.pixelchiss.auth.service

import com.pixelchiss.auth.security.JwtService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class AuthService(
    private val refreshTokenService: RefreshTokenService,
    private val userService: UserService,
    private val jwtService: JwtService
) {
    fun refreshAccessToken(token: String): String {
        val refreshToken = refreshTokenService.findValidToken(token)
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Refresh token inválido o expirado"
            )

        val user = userService.findById(refreshToken.userId)
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Usuario no encontrado"
            )

        return jwtService.generateAccessToken(
            userId = requireNotNull(user.id),
            email = user.email
        )
    }

    fun logout(token: String) {
        refreshTokenService.revoke(token)
    }
}
