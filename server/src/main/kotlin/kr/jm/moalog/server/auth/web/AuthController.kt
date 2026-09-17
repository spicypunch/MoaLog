package kr.jm.moalog.server.auth.web

import kr.jm.moalog.core.contracts.AuthExchangeRequest
import kr.jm.moalog.core.contracts.AuthRefreshRequest
import kr.jm.moalog.core.contracts.AuthTokenResponse
import kr.jm.moalog.core.contracts.AuthenticatedUserDto
import kr.jm.moalog.core.contracts.AuthChallengeResponse
import kr.jm.moalog.core.contracts.AuthProvider
import kr.jm.moalog.core.contracts.ClientDeviceDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.application.AuthService
import kr.jm.moalog.server.auth.application.LoginChallengeService
import kr.jm.moalog.server.account.application.AccountDeletionService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import org.springframework.web.bind.annotation.RequestHeader
import jakarta.servlet.http.HttpServletRequest

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val loginChallengeService: LoginChallengeService,
    private val accountDeletionService: AccountDeletionService,
) {
    @PostMapping("/challenge")
    fun challenge(
        @RequestHeader("X-MoaLog-Device-Id") deviceId: UUID,
        request: HttpServletRequest,
    ): AuthChallengeResponse = loginChallengeService.issue(deviceId, request.remoteAddr)

    @PostMapping("/exchange")
    fun exchange(@Valid @RequestBody request: AuthExchangeWebRequest): AuthTokenResponse =
        authService.exchange(request.toContract())

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: AuthRefreshWebRequest): AuthTokenResponse =
        authService.refresh(AuthRefreshRequest(request.refreshToken))

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): AuthenticatedUserDto =
        authService.currentUser(UUID.fromString(jwt.subject))

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeSession(@AuthenticationPrincipal jwt: Jwt, @PathVariable sessionId: UUID) {
        authService.revokeSession(UUID.fromString(jwt.subject), sessionId)
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAccount(@AuthenticationPrincipal jwt: Jwt) {
        accountDeletionService.delete(UUID.fromString(jwt.subject))
    }
}

data class AuthExchangeWebRequest(
    @field:Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val challengeId: String,
    val provider: AuthProvider,
    @field:NotBlank
    @field:Size(max = 16_384)
    val idToken: String,
    @field:Valid
    val device: ClientDeviceWebRequest,
) {
    fun toContract() = AuthExchangeRequest(
        provider = provider,
        challengeId = UuidString(challengeId),
        idToken = idToken,
        device = device.toContract(),
    )

    override fun toString(): String =
        "AuthExchangeWebRequest(challengeId=$challengeId, provider=$provider, idToken=<redacted>, device=$device)"
}

data class ClientDeviceWebRequest(
    @field:Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    val deviceId: String,
    val platform: ClientPlatform,
    @field:NotBlank
    @field:Size(max = 80)
    val appVersion: String,
) {
    fun toContract() = ClientDeviceDto(UuidString(deviceId), platform, appVersion)
}

data class AuthRefreshWebRequest(
    @field:NotBlank
    @field:Size(max = 512)
    val refreshToken: String,
) {
    override fun toString(): String = "AuthRefreshWebRequest(refreshToken=<redacted>)"
}
