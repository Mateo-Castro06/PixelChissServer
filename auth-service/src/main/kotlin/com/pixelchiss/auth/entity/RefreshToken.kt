package com.pixelchiss.auth.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "refresh_tokens")
class RefreshToken(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val token: String,

    @Column(nullable = false)
    val userId: Long,

    @Column(nullable = false)
    val expiresAt: Instant,

    @Column(nullable = false)
    var revoked: Boolean = false
)