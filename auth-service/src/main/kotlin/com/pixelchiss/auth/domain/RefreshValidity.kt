package com.pixelchiss.auth.domain

import java.time.Instant

object RefreshValidity {
    const val LIFETIME_SECONDS = 30L * 24 * 60 * 60

    fun expired(expiresAt: Instant, now: Instant): Boolean = expiresAt.isBefore(now)
}
