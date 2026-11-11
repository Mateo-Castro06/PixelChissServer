package com.pixelchiss.auth.application

/** The application needs a signed credential; it does not load RSA keys. */
interface AccessTokenIssuer {
    fun generateAccessToken(userId: Long, email: String): String
}
