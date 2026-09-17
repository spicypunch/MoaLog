package kr.jm.moalog.server.sync.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
import kr.jm.moalog.core.contracts.DEFAULT_SYNC_PULL_LIMIT
import kr.jm.moalog.core.contracts.MAX_SYNC_PULL_LIMIT
import kr.jm.moalog.core.contracts.SyncPullResponse
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.SyncPushResponse
import kr.jm.moalog.server.sync.application.SyncService
import kr.jm.moalog.server.sync.application.SyncDeviceMismatchException
import kr.jm.moalog.server.auth.application.AuthTokenService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/households/{householdId}/sync")
class SyncController(
    private val syncService: SyncService,
) {
    @PostMapping("/push")
    fun push(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable householdId: UUID,
        @Valid @RequestBody request: SyncPushRequest,
    ): SyncPushResponse {
        val sessionId = runCatching {
            UUID.fromString(jwt.getClaimAsString(AuthTokenService.SESSION_ID_CLAIM))
        }.getOrElse { throw SyncDeviceMismatchException() }
        return syncService.push(UUID.fromString(jwt.subject), sessionId, householdId, request)
    }

    @GetMapping("/pull")
    fun pull(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable householdId: UUID,
        @RequestParam(defaultValue = "0") @PositiveOrZero cursor: Long,
        @RequestParam(defaultValue = "$DEFAULT_SYNC_PULL_LIMIT")
        @Min(1)
        @Max(MAX_SYNC_PULL_LIMIT.toLong())
        limit: Int,
    ): SyncPullResponse = syncService.pull(UUID.fromString(jwt.subject), householdId, cursor, limit)
}
