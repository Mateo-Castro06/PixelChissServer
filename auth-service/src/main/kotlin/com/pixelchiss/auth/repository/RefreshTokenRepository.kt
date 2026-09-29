package com.pixelchiss.auth.repository

import com.pixelchiss.auth.entity.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByToken(token: String): Optional<RefreshToken>
}