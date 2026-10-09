package com.pixelchiss.auth.controller

import com.pixelchiss.auth.dto.LogoutRequest
import com.pixelchiss.auth.dto.MessageResponse
import com.pixelchiss.auth.dto.RefreshTokenRequest
import com.pixelchiss.auth.dto.TokenResponse
import com.pixelchiss.auth.dto.UserProfileResponse
import com.pixelchiss.auth.dto.UpdateDisplayNameRequest
import com.pixelchiss.auth.repository.UserRepository
import com.pixelchiss.auth.security.JwtService
import com.pixelchiss.auth.service.RefreshTokenService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
            userId = requireNotNull(user.id),
            email = user.email
        )

        return TokenResponse(
            accessToken = accessToken
        )
    }

    @Operation(
        summary = "Cerrar sesión",
        description = "Revoca el refresh token proporcionado."
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Solicitud de cierre de sesión procesada"
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

    @Operation(
        summary = "Consultar mi perfil",
        description = "Obtiene el perfil de la cuenta autenticada mediante su access token.",
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Perfil consultado correctamente"
            ),
            ApiResponse(
                responseCode = "401",
                description = "Token ausente, inválido o expirado"
            ),
            ApiResponse(
                responseCode = "404",
                description = "Usuario no encontrado"
            )
        ]
    )
    @GetMapping("/me")
    fun getMyProfile(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt
    ): UserProfileResponse {

        val userId = jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "ID de usuario inválido"
            )

        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Usuario no encontrado"
            )
        }

        return UserProfileResponse(
            id = requireNotNull(user.id),
            email = user.email,
            displayName = user.displayName,
            createdAt = user.createdAt
        )
    }

    @Operation(
        summary = "Asignar o actualizar mi nombre",
        description = """
            Permite al usuario autenticado establecer su displayName.
            Admite entre 3 y 24 caracteres: letras ASCII, números y guion bajo.
            El usuario se identifica mediante el JWT validado por Spring Security.
        """,
        security = [SecurityRequirement(name = "bearerAuth")]
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Nombre actualizado correctamente"
            ),
            ApiResponse(
                responseCode = "400",
                description = "El nombre no cumple el formato requerido"
            ),
            ApiResponse(
                responseCode = "401",
                description = "Token ausente, inválido o expirado"
            ),
            ApiResponse(
                responseCode = "404",
                description = "Usuario no encontrado"
            )
        ]
    )
    @PutMapping("/me/display-name")
    fun updateDisplayName(
        @Parameter(hidden = true)
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: UpdateDisplayNameRequest
    ): UserProfileResponse {

        val userId = jwt.subject?.toLongOrNull()
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "ID de usuario inválido"
            )

        val displayName = request.displayName.trim()

        if (!displayName.matches(Regex("^[A-Za-z0-9_]{3,24}$"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El nombre debe tener entre 3 y 24 caracteres: letras, números o guion bajo"
            )
        }

        val user = userRepository.findById(userId).orElseThrow {
            ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Usuario no encontrado"
            )
        }

        user.displayName = displayName

        val savedUser = userRepository.save(user)

        return UserProfileResponse(
            id = requireNotNull(savedUser.id),
            email = savedUser.email,
            displayName = savedUser.displayName,
            createdAt = savedUser.createdAt
        )
    }
}
