package kr.jm.moalog.server.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI
import kr.jm.moalog.server.auth.application.AuthProblemException
import kr.jm.moalog.server.household.application.HouseholdProblemException
import kr.jm.moalog.server.sync.application.SyncProblemException

data class InvalidField(
    val field: String,
    val message: String,
)

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(AuthProblemException::class)
    fun handleAuthProblem(
        exception: AuthProblemException,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(exception.status, exception.safeDetail).apply {
        title = exception.problemTitle
        type = URI.create(exception.problemType)
        instance = URI.create(request.requestURI)
    }

    @ExceptionHandler(HouseholdProblemException::class)
    fun handleHouseholdProblem(
        exception: HouseholdProblemException,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(exception.status, exception.safeDetail).apply {
        title = exception.problemTitle
        type = URI.create(exception.problemType)
        instance = URI.create(request.requestURI)
    }

    @ExceptionHandler(SyncProblemException::class)
    fun handleSyncProblem(
        exception: SyncProblemException,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(exception.status, exception.safeDetail).apply {
        title = exception.problemTitle
        type = URI.create(exception.problemType)
        instance = URI.create(request.requestURI)
    }

    override fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        val problem = validationProblem(
            instance = request.requestUri(),
            errors = exception.bindingResult.fieldErrors
            .map { InvalidField(field = it.field, message = it.defaultMessage ?: "Invalid value") }
            .distinct()
            .sortedBy { it.field },
        )
        return ResponseEntity(problem, headers, problem.status)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleInvalidConstraint(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ProblemDetail = validationProblem(
        instance = URI.create(request.requestURI),
        errors = exception.constraintViolations
            .map { InvalidField(field = it.propertyPath.toString(), message = it.message) }
            .distinct()
            .sortedBy { it.field },
    )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidContract(
        exception: IllegalArgumentException,
        request: HttpServletRequest,
    ): ProblemDetail = validationProblem(
        instance = URI.create(request.requestURI),
        errors = emptyList(),
    )

    private fun validationProblem(
        instance: URI,
        errors: List<InvalidField>,
    ): ProblemDetail = ProblemDetail
        .forStatusAndDetail(HttpStatus.BAD_REQUEST, "One or more request fields are invalid.")
        .apply {
            title = "Invalid request"
            type = URI.create("urn:moalog:problem:validation")
            this.instance = instance
            setProperty("errors", errors)
        }

    private fun WebRequest.requestUri(): URI = URI.create(
        (this as? ServletWebRequest)?.request?.requestURI ?: "/",
    )
}
