package com.pixelchiss.auth.service

import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.repository.UserRepository
import org.springframework.stereotype.Service

@Service
class UserService(
    private val userRepository: UserRepository
) {

    fun findByGoogleId(googleId: String): User? {
        return userRepository.findByGoogleId(googleId).orElse(null)
    }

    fun findByEmail(email: String): User? {
        return userRepository.findByEmail(email).orElse(null)
    }

    fun createUser(
        googleId: String, email: String, displayName: String
    ): User {
        val user = User(
            googleId = googleId, email = email, displayName = displayName
        )

        return userRepository.save(user)
    }
}