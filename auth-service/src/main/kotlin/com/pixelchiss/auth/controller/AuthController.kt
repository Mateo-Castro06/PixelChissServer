package com.pixelchiss.auth.controller

import com.pixelchiss.auth.dto.LogoutRequest
import com.pixelchiss.auth.dto.MessageResponse
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
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val refreshTokenService: RefreshTokenService,
    private val userRepository: UserRepository,
    private val jwtService: JwtService
) {
    @Operation(
        summary = "Renovar access token",
        description = "Genera un nuevo access token utilizando un refresh token válido."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Access token renovado correctamente"
            ),
            ApiResponse(
                responseCode = "401",
                description = "Refresh token inválido, expirado o usuario inexistente"
            )
        ]
    )
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
    @Operation(
        summary = "Cerrar sesión",
        description = "Revoca el refresh token proporcionado y cierra la sesión."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Sesión cerrada correctamente"
            )
        ]
    )

    @PostMapping("/logout")
    fun logout(
        @RequestBody request: LogoutRequest
    ): MessageResponse {

        refreshTokenService.revoke(request.refreshToken)

        return MessageResponse(
            message = "Sesión cerrada correctamente"
        )
    }
}

