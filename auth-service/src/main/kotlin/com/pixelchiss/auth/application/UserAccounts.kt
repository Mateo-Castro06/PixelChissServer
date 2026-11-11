package com.pixelchiss.auth.application

import com.pixelchiss.auth.entity.User

/** Account operations needed by the profile and identity use cases. */
interface UserAccounts {
    fun findById(id: Long): User?
    fun findByGoogleId(googleId: String): User?
    fun findByEmail(email: String): User?
    fun save(user: User): User
}
