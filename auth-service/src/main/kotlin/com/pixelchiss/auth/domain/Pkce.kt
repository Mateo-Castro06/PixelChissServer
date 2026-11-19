package com.pixelchiss.auth.domain

import java.security.MessageDigest
import java.util.Base64

object Pkce {
    fun validChallenge(value: String) = Regex("[A-Za-z0-9_-]{43}").matches(value)
    fun validVerifier(value: String) = Regex("[A-Za-z0-9._~-]{43,128}").matches(value)
    fun hash(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)))
    fun matches(verifier: String, challenge: String) = validVerifier(verifier) &&
        MessageDigest.isEqual(hash(verifier).toByteArray(), challenge.toByteArray())
}
