package com.pixelchiss.auth.service

import com.pixelchiss.auth.dto.UserProfileResponse
import com.pixelchiss.auth.entity.User
import com.pixelchiss.auth.repository.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class UserService(
    private val userRepository: UserRepository
) {
    private companion object {
        val DISPLAY_NAME_PATTERN = Regex("^[A-Za-z0-9_]{3,24}$")
    }

    fun findById(userId: Long): User? = userRepository.findById(userId).orElse(null)

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

    fun getProfile(userId: Long): UserProfileResponse = requireProfileUser(userId).toProfileResponse()

    fun updateDisplayName(userId: Long, requestedName: String): UserProfileResponse {
        val displayName = requestedName.trim()
        if (!DISPLAY_NAME_PATTERN.matches(displayName)) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El nombre debe tener entre 3 y 24 caracteres: letras, números o guion bajo"
            )
        }

        val user = requireProfileUser(userId)
        user.displayName = displayName
        return userRepository.save(user).toProfileResponse()
    }

    private fun requireProfileUser(userId: Long): User = findById(userId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado")

    private fun User.toProfileResponse() = UserProfileResponse(
        id = requireNotNull(id),
        email = email,
        displayName = displayName,
        createdAt = createdAt
    )
}
