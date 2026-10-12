package com.pixelchiss.auth.support

import com.pixelchiss.auth.AuthServiceApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Test keys are generated independently of the production keys. */
object TestKeys {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey = pair.public as RSAPublicKey
    private val directory = Files.createTempDirectory("pixelchiss-auth-tests-").also { it.toFile().deleteOnExit() }
    val privateKeyPath = directory.resolve("private.pem").also {
        Files.writeString(it, pem("PRIVATE KEY", pair.private.encoded))
        it.toFile().deleteOnExit()
    }
    val publicKeyPath = directory.resolve("public.pem").also {
        Files.writeString(it, pem("PUBLIC KEY", pair.public.encoded))
        it.toFile().deleteOnExit()
    }

    private fun pem(type: String, bytes: ByteArray): String =
        "-----BEGIN $type-----\n${Base64.getMimeEncoder().encodeToString(bytes)}\n-----END $type-----\n"
}

/** Requires a dedicated PostgreSQL test database; never reads infrastructure/.env. */
@SpringBootTest(classes = [AuthServiceApplication::class])
abstract class AuthIntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun testProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                System.getenv("AUTH_TEST_DATABASE_URL") ?: "jdbc:postgresql://127.0.0.1:55432/auth_refactor_tests"
            }
            registry.add("spring.datasource.username") { System.getenv("AUTH_TEST_DATABASE_USER") ?: "auth_test" }
            registry.add("spring.datasource.password") { System.getenv("AUTH_TEST_DATABASE_PASSWORD") ?: "auth_test" }
            registry.add("spring.security.oauth2.client.registration.google.client-id") { "test-client" }
            registry.add("spring.security.oauth2.client.registration.google.client-secret") { "test-secret" }
            registry.add("spring.security.oauth2.client.registration.google.redirect-uri") {
                "http://localhost:8081/login/oauth2/code/google"
            }
            registry.add("jwt.private-key") { TestKeys.privateKeyPath.toString() }
            registry.add("jwt.public-key") { TestKeys.publicKeyPath.toString() }
            registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location") {
                TestKeys.publicKeyPath.toUri().toString()
            }
            registry.add("eureka.client.enabled") { false }
            registry.add("eureka.client.service-url.defaultZone") { "http://127.0.0.1:8761/eureka/" }
            registry.add("spring.cloud.discovery.enabled") { false }
        }
    }
}
