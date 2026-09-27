package com.pixelchiss.auth.controller

import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/test")
class TestController {

    @GetMapping("/protected")
    fun protected(authentication: Authentication): Map<String, Any?> {
        return mapOf(
            "message" to "JWT válido",
            "user" to authentication.name
        )
    }
}