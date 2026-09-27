package com.pixelchiss.auth.repository

import com.pixelchiss.auth.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserRepository : JpaRepository<User, Long> {

    fun findByGoogleId(googleId: String): Optional<User>

    fun findByEmail(email: String): Optional<User>
}