package com.pixelchiss.auth.entity

import jakarta.persistence.*
import java.time.Instant

/** Only hashes of bearer codes/states are stored; no Google codes or tokens. */
@Entity
@Table(name = "login_attempts", indexes = [Index(name="login_attempt_expiry", columnList="expires_at")])
class LoginAttempt(
    @Id val id: String,
    @Column(nullable=false) val purpose: String,
    @Column(nullable=false) val challenge: String,
    @Column(nullable=false) val nonce: String,
    @Column(nullable=false) val redirectUri: String,
    @Column(name="expires_at",nullable=false) val expiresAt: Instant,
    val userId: Long? = null,
    var consumedAt: Instant? = null
)
