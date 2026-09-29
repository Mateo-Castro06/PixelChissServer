package com.pixelchiss.auth.controller

import com.pixelchiss.auth.dto.RefreshTokenRequest
import com.pixelchiss.auth.dto.TokenResponse
import com.pixelchiss.auth.security.JwtService
import com.pixelchiss.auth.service.RefreshTokenService
import com.pixelchiss.auth.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val refreshTokenService: RefreshTokenService,
    private val userRepository: UserRepository,
    private val jwtService: JwtService
) {

    @PostMapping("/refresh")
    fun refresh(
        @RequestBody request: RefreshTokenRequest
    ): TokenResponse {

        val refreshToken = refreshTokenService.findValidToken(
            request.refreshToken
        ) ?: throw ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Refresh token inválido o expirado"
        )

        val user = userRepository.findById(
            refreshToken.userId
        ).orElseThrow {
            ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Usuario no encontrado"
            )
        }

        val accessToken = jwtService.generateAccessToken(
            userId = user.id!!,
            email = user.email
        )

        return TokenResponse(
            accessToken = accessToken
        )
    }
    @PostMapping("/logout")
    fun logout(
        @RequestBody request: RefreshTokenRequest
    ): Map<String, String> {

        refreshTokenService.revoke(request.refreshToken)

        return mapOf(
            "message" to "Sesión cerrada correctamente"
        )
    }
}

