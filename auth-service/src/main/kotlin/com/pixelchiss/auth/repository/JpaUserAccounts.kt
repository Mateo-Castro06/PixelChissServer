package com.pixelchiss.auth.repository

import com.pixelchiss.auth.application.UserAccounts
import com.pixelchiss.auth.entity.User
import org.springframework.stereotype.Component

@Component
class JpaUserAccounts(private val users: UserRepository) : UserAccounts {
    override fun findById(id: Long): User? = users.findById(id).orElse(null)
    override fun findByGoogleId(googleId: String): User? = users.findByGoogleId(googleId).orElse(null)
    override fun findByEmail(email: String): User? = users.findByEmail(email).orElse(null)
    override fun save(user: User): User = users.save(user)
}
