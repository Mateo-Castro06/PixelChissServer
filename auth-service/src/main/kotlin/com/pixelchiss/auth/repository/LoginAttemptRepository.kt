package com.pixelchiss.auth.repository

import com.pixelchiss.auth.entity.LoginAttempt
import java.time.Instant
import org.springframework.data.jpa.repository.*
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface LoginAttemptRepository : JpaRepository<LoginAttempt, String> {
    @Transactional
    @Modifying
    @Query("update LoginAttempt a set a.consumedAt=:now where a.id=:id and a.consumedAt is null and a.expiresAt>:now")
    fun consume(@Param("id") id: String, @Param("now") now: Instant): Int
}
