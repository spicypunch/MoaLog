package kr.jm.moalog.server.auth.application

import org.springframework.http.HttpStatus

open class AuthProblemException(
    val status: HttpStatus,
    val problemType: String,
    val problemTitle: String,
    val safeDetail: String,
) : RuntimeException(safeDetail)

class InvalidProviderCredentialException : AuthProblemException(
    status = HttpStatus.UNAUTHORIZED,
    problemType = "urn:moalog:problem:invalid-provider-credential",
    problemTitle = "Invalid provider credential",
    safeDetail = "The provider credential is invalid or expired.",
)

open class InvalidRefreshTokenException : AuthProblemException(
    status = HttpStatus.UNAUTHORIZED,
    problemType = "urn:moalog:problem:invalid-refresh-token",
    problemTitle = "Invalid refresh token",
    safeDetail = "The refresh token is invalid or expired.",
)

class RefreshTokenReplayException : InvalidRefreshTokenException()

class SessionReauthenticationRequiredException : InvalidRefreshTokenException()

class LoginChallengeCapacityException : AuthProblemException(
    status = HttpStatus.TOO_MANY_REQUESTS,
    problemType = "urn:moalog:problem:too-many-login-challenges",
    problemTitle = "Too many login attempts",
    safeDetail = "Too many login attempts are in progress. Please try again later.",
)

class SessionAccessDeniedException : AuthProblemException(
    status = HttpStatus.FORBIDDEN,
    problemType = "urn:moalog:problem:forbidden",
    problemTitle = "Access denied",
    safeDetail = "You do not have permission to access this session.",
)

class AuthenticatedUserNotFoundException : AuthProblemException(
    status = HttpStatus.UNAUTHORIZED,
    problemType = "urn:moalog:problem:unauthorized",
    problemTitle = "Authentication required",
    safeDetail = "The authenticated user is no longer available.",
)
