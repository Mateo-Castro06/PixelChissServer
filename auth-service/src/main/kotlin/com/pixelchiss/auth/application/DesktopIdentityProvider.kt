package com.pixelchiss.auth.application

/** Google exchange/verification stays behind an application port. */
interface DesktopIdentityProvider {
    fun clientId(): String
    fun exchange(code: String, verifier: String, redirectUri: String, nonce: String): GoogleIdentity
}
data class GoogleIdentity(val subject: String, val email: String, val name: String)
