package kr.jm.moalog.server

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import kr.jm.moalog.server.auth.config.AuthTokenConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtException
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date

class ProviderJwtDecoderTest {
    private lateinit var signingKey: RSAKey
    private lateinit var server: HttpServer
    private lateinit var jwkSetUri: String

    @BeforeEach
    fun setUp() {
        signingKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                val body = JWKSet(signingKey.toPublicJWK()).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        jwkSetUri = "http://127.0.0.1:${server.address.port}/jwks"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `provider decoder verifies signature issuer audience and time`() {
        val decoder = AuthTokenConfig().providerDecoder(jwkSetUri, setOf(ISSUER), AUDIENCE)

        val decoded = decoder.decode(signedToken(signingKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(120)))

        assertEquals("provider-subject", decoded.subject)
        assertThrows(JwtException::class.java) {
            decoder.decode(signedToken(RSAKeyGenerator(2048).keyID("other").generate(), ISSUER, AUDIENCE, Instant.now().plusSeconds(120)))
        }
        assertThrows(JwtException::class.java) {
            decoder.decode(signedToken(signingKey, "https://wrong.example", AUDIENCE, Instant.now().plusSeconds(120)))
        }
        assertThrows(JwtException::class.java) {
            decoder.decode(signedToken(signingKey, ISSUER, "wrong-audience", Instant.now().plusSeconds(120)))
        }
        assertThrows(JwtException::class.java) {
            decoder.decode(signedToken(signingKey, ISSUER, AUDIENCE, Instant.now().minusSeconds(120)))
        }
    }

    private fun signedToken(key: RSAKey, issuer: String, audience: String, expiresAt: Instant): String {
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("provider-subject")
            .issueTime(Date.from(Instant.now().minusSeconds(5)))
            .expirationTime(Date.from(expiresAt))
            .build()
        return SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(),
            claims,
        ).apply { sign(RSASSASigner(key)) }.serialize()
    }

    companion object {
        private const val ISSUER = "https://issuer.example"
        private const val AUDIENCE = "expected-client"
    }
}
