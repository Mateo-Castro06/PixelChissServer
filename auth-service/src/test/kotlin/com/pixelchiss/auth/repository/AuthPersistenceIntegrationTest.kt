package com.pixelchiss.auth.repository

import com.pixelchiss.auth.entity.RefreshToken
import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.support.AuthIntegrationTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Transactional
class AuthPersistenceIntegrationTest : AuthIntegrationTest() {
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var refreshTokens: RefreshTokenRepository

    @ParameterizedTest
    @ValueSource(strings = ["googleId", "email"])
    fun `PostgreSQL enforces existing unique identity constraints`(field: String) {
        val existing = users.saveAndFlush(User(
            googleId = UUID.randomUUID().toString(), email = "${UUID.randomUUID()}@example.com", displayName = "Original"
        ))
        val duplicate = User(
            googleId = if (field == "googleId") existing.googleId else UUID.randomUUID().toString(),
            email = if (field == "email") existing.email else "${UUID.randomUUID()}@example.com",
            displayName = "Duplicate"
        )
        assertThrows(DataIntegrityViolationException::class.java) { users.saveAndFlush(duplicate) }
    }

    @Test
    fun `PostgreSQL enforces refresh token uniqueness`() {
        val value = UUID.randomUUID().toString()
        refreshTokens.saveAndFlush(RefreshToken(token = value, userId = 42, expiresAt = Instant.now().plusSeconds(60)))
        assertThrows(DataIntegrityViolationException::class.java) {
            refreshTokens.saveAndFlush(RefreshToken(token = value, userId = 43, expiresAt = Instant.now().plusSeconds(60)))
        }
    }
}
