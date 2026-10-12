package com.pixelchiss.auth.service

import com.pixelchiss.auth.entity.RefreshToken
import com.pixelchiss.auth.repository.RefreshTokenRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.time.Instant
import java.util.Optional
import java.util.UUID

class RefreshTokenServiceTest {
    private val repository = mock(RefreshTokenRepository::class.java)
    private val service = RefreshTokenService(repository)

    @Test
    fun `creation persists a UUID linked to user with thirty days lifetime`() {
        doAnswer { it.getArgument<RefreshToken>(0) }.`when`(repository).save(any(RefreshToken::class.java))
        val before = Instant.now()
        val token = service.create(42)
        val after = Instant.now()
        assertEquals(42L, token.userId)
        assertEquals(token.token, UUID.fromString(token.token).toString())
        assertFalse(token.revoked)
        val lifetime = 30L * 24 * 60 * 60
        assertFalse(token.expiresAt.isBefore(before.plusSeconds(lifetime)))
        assertFalse(token.expiresAt.isAfter(after.plusSeconds(lifetime)))
        verify(repository).save(token)
    }

    @Test
    fun `missing token is invalid`() {
        `when`(repository.findByToken("missing")).thenReturn(Optional.empty())
        assertNull(service.findValidToken("missing"))
    }

    @Test
    fun `revoked token is invalid even before expiration`() {
        val token = token(Instant.now().plusSeconds(60), revoked = true)
        `when`(repository.findByToken(token.token)).thenReturn(Optional.of(token))
        assertNull(service.findValidToken(token.token))
    }

    @Test
    fun `expired token is invalid`() {
        val token = token(Instant.now().minusSeconds(1))
        `when`(repository.findByToken(token.token)).thenReturn(Optional.of(token))
        assertNull(service.findValidToken(token.token))
    }

    @Test
    fun `valid token is returned without rotation or persistence writes`() {
        val token = token(Instant.now().plusSeconds(60))
        `when`(repository.findByToken(token.token)).thenReturn(Optional.of(token))
        assertSame(token, service.findValidToken(token.token))
        verify(repository, never()).save(any(RefreshToken::class.java))
    }

    @Test
    fun `revocation of missing token is a no-op`() {
        `when`(repository.findByToken("missing")).thenReturn(Optional.empty())
        service.revoke("missing")
        verify(repository, never()).save(any(RefreshToken::class.java))
    }

    @Test
    fun `revocation marks and saves the same token including repeated revocation`() {
        val token = token(Instant.now().plusSeconds(60))
        `when`(repository.findByToken(token.token)).thenReturn(Optional.of(token))
        service.revoke(token.token)
        service.revoke(token.token)
        val captor = ArgumentCaptor.forClass(RefreshToken::class.java)
        verify(repository, times(2)).save(captor.capture())
        assertTrue(captor.allValues.all { it === token && it.revoked })
    }

    private fun token(expiresAt: Instant, revoked: Boolean = false) =
        RefreshToken(token = "refresh", userId = 42, expiresAt = expiresAt, revoked = revoked)
}
