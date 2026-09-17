package kr.jm.moalog.server.sync.application

import org.springframework.http.HttpStatus

open class SyncProblemException(
    val status: HttpStatus,
    val problemType: String,
    val problemTitle: String,
    val safeDetail: String,
) : RuntimeException(safeDetail)

class InvalidSyncRequestException : SyncProblemException(
    status = HttpStatus.BAD_REQUEST,
    problemType = "urn:moalog:problem:validation",
    problemTitle = "Invalid request",
    safeDetail = "One or more synchronization fields are invalid.",
)

class SyncVersionConflictException : SyncProblemException(
    status = HttpStatus.CONFLICT,
    problemType = "urn:moalog:problem:sync-version-conflict",
    problemTitle = "Synchronization version conflict",
    safeDetail = "The synchronized entity changed after it was loaded or is unavailable.",
)

class SyncIdempotencyConflictException : SyncProblemException(
    status = HttpStatus.CONFLICT,
    problemType = "urn:moalog:problem:sync-idempotency-conflict",
    problemTitle = "Synchronization idempotency conflict",
    safeDetail = "The mutation identifier was already used for different content.",
)

class UnsupportedSyncEntityTypeException : SyncProblemException(
    status = HttpStatus.UNPROCESSABLE_ENTITY,
    problemType = "urn:moalog:problem:sync-entity-type-unsupported",
    problemTitle = "Synchronization entity type unsupported",
    safeDetail = "This entity type cannot be changed through generic synchronization.",
)

class SyncDeviceMismatchException : SyncProblemException(
    status = HttpStatus.FORBIDDEN,
    problemType = "urn:moalog:problem:sync-device-mismatch",
    problemTitle = "Synchronization device mismatch",
    safeDetail = "The synchronization device does not match the authenticated session.",
)

class InvalidSyncPayloadException : SyncProblemException(
    status = HttpStatus.UNPROCESSABLE_ENTITY,
    problemType = "urn:moalog:problem:sync-payload-invalid",
    problemTitle = "Synchronization payload invalid",
    safeDetail = "The synchronized entity payload is invalid or violates a data relationship.",
)
