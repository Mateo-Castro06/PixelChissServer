package com.pixelchiss.auth.security

import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

@Service
class JwtService(
    @Value("\${jwt.private-key}")
    private val privateKeyPath: String
) {

    private val privateKey: PrivateKey by lazy {
        loadPrivateKey(privateKeyPath)
    }

    fun generateAccessToken(userId: Long, email: String): String {
        val now = Date()
        val expiration = Date(now.time + 15 * 60 * 1000)

        return Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(now)
            .expiration(expiration)
            .signWith(privateKey)
            .compact()
    }

    private fun loadPrivateKey(path: String): PrivateKey {
        val pem = Files.readString(Paths.get(path))
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val keyBytes = Base64.getDecoder().decode(pem)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)

        return KeyFactory
            .getInstance("RSA")
            .generatePrivate(keySpec)
    }
}