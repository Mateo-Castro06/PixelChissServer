package com.pixelchiss.auth.controller

import com.pixelchiss.auth.service.LoginFlowService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class LoginController(private val login: LoginFlowService) {
    data class DesktopStart(val clientPort: Int, val codeChallenge: String)
    data class DesktopExchange(val state: String, val code: String, val codeVerifier: String)
    data class WebExchange(val code: String, val codeVerifier: String)
    @PostMapping("/desktop/start")
    fun start(@RequestBody input: DesktopStart) = noStore(login.start(input.clientPort,input.codeChallenge))
    @PostMapping("/desktop/exchange")
    fun desktop(@RequestBody input: DesktopExchange) = noStore(login.exchangeDesktop(input.state,input.code,input.codeVerifier))
    @PostMapping("/login/exchange")
    fun web(@RequestBody input: WebExchange) = noStore(login.exchangeWeb(input.code,input.codeVerifier))
    private fun <T:Any> noStore(value:T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("Pragma","no-cache").body(value)
}
