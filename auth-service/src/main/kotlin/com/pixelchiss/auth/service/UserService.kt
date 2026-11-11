package com.pixelchiss.auth.service

import com.pixelchiss.auth.application.UserAccounts
import com.pixelchiss.auth.domain.DisplayName
import com.pixelchiss.auth.dto.UserProfileResponse
import com.pixelchiss.auth.entity.User
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class UserService(
    private val accounts: UserAccounts
) {
    fun findById(userId: Long): User? = accounts.findById(userId)

    fun findByGoogleId(googleId: String): User? = accounts.findByGoogleId(googleId)

    fun findByEmail(email: String): User? = accounts.findByEmail(email)

    fun createUser(
        googleId: String, email: String, displayName: String
    ): User {
        val user = User(
            googleId = googleId, email = email, displayName = displayName
        )

        return accounts.save(user)
    }

    fun getProfile(userId: Long): UserProfileResponse = requireProfileUser(userId).toProfileResponse()

    fun updateDisplayName(userId: Long, requestedName: String): UserProfileResponse {
        val displayName = DisplayName.forUpdate(requestedName)
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "El nombre debe tener entre 3 y 24 caracteres: letras, números o guion bajo"
            )

        val user = requireProfileUser(userId)
        user.displayName = displayName.value
        return accounts.save(user).toProfileResponse()
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
