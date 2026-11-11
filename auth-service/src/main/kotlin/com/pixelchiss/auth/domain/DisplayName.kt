package com.pixelchiss.auth.domain

/** Policy for explicit profile edits; the initial provider name remains independent. */
@JvmInline
value class DisplayName private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[A-Za-z0-9_]{3,24}$")

        fun forUpdate(raw: String): DisplayName? =
            raw.trim().takeIf(pattern::matches)?.let(::DisplayName)
    }
}
