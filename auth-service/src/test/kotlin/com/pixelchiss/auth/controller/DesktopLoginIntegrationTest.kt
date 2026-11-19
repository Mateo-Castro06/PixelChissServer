package com.pixelchiss.auth.controller

import com.pixelchiss.auth.application.*
import com.pixelchiss.auth.domain.Pkce
import com.pixelchiss.auth.entity.LoginAttempt
import com.pixelchiss.auth.repository.LoginAttemptRepository
import com.pixelchiss.auth.service.LoginFlowService
import com.pixelchiss.auth.support.AuthIntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.*
import org.springframework.http.*
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*

@AutoConfigureMockMvc
@Import(DesktopLoginIntegrationTest.Provider::class)
class DesktopLoginIntegrationTest : AuthIntegrationTest() {
    @TestConfiguration class Provider {
        @Bean @Primary fun fakeGoogle() = object : DesktopIdentityProvider {
            override fun clientId()="test-desktop-client"
            override fun exchange(code:String,verifier:String,redirectUri:String,nonce:String):GoogleIdentity {
                assertTrue(redirectUri.startsWith("http://127.0.0.1:"));assertTrue(nonce.isNotBlank())
                if(code=="provider-error") throw ResponseStatusException(HttpStatus.UNAUTHORIZED,"Provider failed")
                return GoogleIdentity("lan-$code","$code@example.invalid","Player")
            }
        }
    }
    @Autowired lateinit var mvc:MockMvc
    @Autowired lateinit var json:ObjectMapper
    @Autowired lateinit var login:LoginFlowService
    @Autowired lateinit var attempts:LoginAttemptRepository
    @Autowired lateinit var users:com.pixelchiss.auth.service.UserService
    val verifier="v".repeat(43)
    fun start()=login.start(45678,Pkce.hash(verifier))
    @Test fun `native start fixes loopback and S256 never exposes verifier or secrets`() {
        val result=mvc.perform(post("/api/auth/desktop/start").contentType(MediaType.APPLICATION_JSON)
            .content("""{"clientPort":45678,"codeChallenge":"${Pkce.hash(verifier)}"}"""))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn()
        val body=json.readTree(result.response.contentAsString)
        val url=URLDecoder.decode(body["authorizationUrl"].asString(),Charsets.UTF_8)
        assertTrue(url.startsWith("https://accounts.google.com/"))
        assertTrue(url.contains("redirect_uri=http://127.0.0.1:45678/callback"))
        assertTrue(url.contains("code_challenge_method=S256"));assertTrue(url.contains("state="+body["state"].asString()))
        assertFalse(url.contains("code_verifier"));assertFalse(url.contains("client_secret"))
        assertFalse(url.contains("accessToken"));assertFalse(url.contains("refreshToken"))
    }
    @Test fun `invalid ports challenge and arbitrary callback are not accepted`() {
        for(port in listOf(0,1023,65536)) assertThrows(ResponseStatusException::class.java){login.start(port,Pkce.hash(verifier))}
        assertThrows(ResponseStatusException::class.java){login.start(45678,"plain")}
        mvc.perform(post("/api/auth/desktop/start").contentType(MediaType.APPLICATION_JSON)
            .content("""{"clientPort":45678,"codeChallenge":"plain","redirectUri":"https://evil.invalid"}"""))
            .andExpect(status().isBadRequest)
    }
    @Test fun `exchange returns JSON credentials then cannot replay and refresh remains compatible`() {
        val start=start();val code=UUID.randomUUID().toString()
        val result=mvc.perform(post("/api/auth/desktop/exchange").contentType(MediaType.APPLICATION_JSON)
            .content("""{"state":"${start.state}","code":"$code","codeVerifier":"$verifier"}"""))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store")).andReturn()
        val session=json.readTree(result.response.contentAsString)
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+session["accessToken"].asString()))
            .andExpect(status().isOk).andExpect(jsonPath("$.email").value("$code@example.invalid"))
        assertThrows(ResponseStatusException::class.java){login.exchangeDesktop(start.state,code,verifier)}
        val refresh=session["refreshToken"].asString()
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"$refresh"}"""))
            .andExpect(status().isOk)
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"$refresh"}"""))
            .andExpect(status().isOk)
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""{"refreshToken":"$refresh"}"""))
            .andExpect(status().isUnauthorized)
    }
    @Test fun `wrong verifier state expiry and failed provider cannot bypass one use`() {
        val start=start()
        assertThrows(ResponseStatusException::class.java){login.exchangeDesktop(start.state,"code","x".repeat(43))}
        assertThrows(ResponseStatusException::class.java){login.exchangeDesktop("x".repeat(43),"code",verifier)}
        assertThrows(ResponseStatusException::class.java){login.exchangeWeb(start.state,verifier)}
        assertThrows(ResponseStatusException::class.java){login.exchangeDesktop(start.state,"provider-error",verifier)}
        assertThrows(ResponseStatusException::class.java){login.exchangeDesktop(start.state,"new-code",verifier)}
        val expired=start()
        val old=attempts.findById(Pkce.hash(expired.state)).orElseThrow()
        attempts.save(LoginAttempt(old.id,old.purpose,old.challenge,old.nonce,old.redirectUri,Instant.now().minusSeconds(1)))
        assertThrows(ResponseStatusException::class.java){login.exchangeDesktop(expired.state,"code",verifier)}
    }
    @Test fun `web handoff is bound to client PKCE returns credentials only in JSON and cannot replay`() {
        val code=UUID.randomUUID().toString()
        val desktop=start()
        login.exchangeDesktop(desktop.state,code,verifier)
        val user=users.findByGoogleId("lan-$code")!!
        val handoff=login.issueWebGrant(user.id!!,Pkce.hash(verifier))
        mvc.perform(post("/api/auth/login/exchange").contentType(MediaType.APPLICATION_JSON)
            .content("""{"code":"$handoff","codeVerifier":"${"x".repeat(43)}"}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(post("/api/auth/login/exchange").contentType(MediaType.APPLICATION_JSON)
            .content("""{"code":"$handoff","codeVerifier":"$verifier"}"""))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.accessToken").isString).andExpect(jsonPath("$.refreshToken").isString)
        assertThrows(ResponseStatusException::class.java){login.exchangeWeb(handoff,verifier)}
    }
    @Test fun `concurrent redeemers persist exactly one session`() {
        val start=start();val gate=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        try {
            val futures=(1..2).map {pool.submit<Boolean>{gate.await();try{login.exchangeDesktop(start.state,UUID.randomUUID().toString(),verifier);true}catch(e:ResponseStatusException){false}}}
            gate.countDown();assertEquals(1,futures.count{it.get(10,TimeUnit.SECONDS)})
        } finally {pool.shutdownNow()}
    }
}
