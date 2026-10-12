package com.pixelchiss.auth.service

import com.pixelchiss.auth.entity.RefreshToken
import com.pixelchiss.auth.repository.RefreshTokenRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class RefreshTokenService(
    private val refreshTokenRepository: RefreshTokenRepository
) {
    private companion object {
        const val REFRESH_TOKEN_LIFETIME_SECONDS = 30L * 24 * 60 * 60
    }

    fun create(userId: Long): RefreshToken {
        val refreshToken = RefreshToken(
            token = UUID.randomUUID().toString(),
            userId = userId,
            expiresAt = Instant.now().plusSeconds(REFRESH_TOKEN_LIFETIME_SECONDS)
        )

        return refreshTokenRepository.save(refreshToken)
    }

    fun findValidToken(token: String): RefreshToken? {
        val refreshToken = refreshTokenRepository.findByToken(token).orElse(null)
            ?: return null

        if (refreshToken.revoked || refreshToken.expiresAt.isBefore(Instant.now())) {
            return null
        }

        return refreshToken
    }

    fun revoke(token: String) {
        val refreshToken = refreshTokenRepository.findByToken(token).orElse(null)
            ?: return

        refreshToken.revoked = true
        refreshTokenRepository.save(refreshToken)
    }
}
