package kr.jm.moalog.server.household.application

import org.springframework.http.HttpStatus

open class HouseholdProblemException(
    val status: HttpStatus,
    val problemType: String,
    val problemTitle: String,
    val safeDetail: String,
) : RuntimeException(safeDetail)

class HouseholdNotFoundException : HouseholdProblemException(
    status = HttpStatus.NOT_FOUND,
    problemType = "urn:moalog:problem:household-not-found",
    problemTitle = "Household not found",
    safeDetail = "The household is unavailable.",
)

class HouseholdOwnerRequiredException : HouseholdProblemException(
    status = HttpStatus.FORBIDDEN,
    problemType = "urn:moalog:problem:household-owner-required",
    problemTitle = "Household owner permission required",
    safeDetail = "Only a household owner can perform this operation.",
)

class InvalidHouseholdSettingsException : HouseholdProblemException(
    status = HttpStatus.BAD_REQUEST,
    problemType = "urn:moalog:problem:validation",
    problemTitle = "Invalid request",
    safeDetail = "One or more household fields are invalid.",
)

class HouseholdVersionConflictException : HouseholdProblemException(
    status = HttpStatus.CONFLICT,
    problemType = "urn:moalog:problem:household-version-conflict",
    problemTitle = "Household version conflict",
    safeDetail = "The household changed after it was loaded. Reload it and try again.",
)

class InvitationTargetUnavailableException : HouseholdProblemException(
    status = HttpStatus.CONFLICT,
    problemType = "urn:moalog:problem:invitation-target-unavailable",
    problemTitle = "Invitation target unavailable",
    safeDetail = "The selected household member is already linked or unavailable.",
)

class HouseholdInvitationUnavailableException : HouseholdProblemException(
    status = HttpStatus.CONFLICT,
    problemType = "urn:moalog:problem:household-invitation-unavailable",
    problemTitle = "Household invitation unavailable",
    safeDetail = "The household invitation is invalid, expired, revoked, or already used.",
)
