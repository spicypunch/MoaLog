package kr.jm.moalog.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import java.net.URI

private const val UNAUTHORIZED_TYPE = "urn:moalog:problem:unauthorized"
private const val FORBIDDEN_TYPE = "urn:moalog:problem:forbidden"

@Component
class ProblemDetailAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.writeProblem(
            objectMapper = objectMapper,
            problem = apiProblem(
                status = HttpStatus.UNAUTHORIZED,
                title = "Authentication required",
                detail = "Authentication is required to access this resource.",
                type = UNAUTHORIZED_TYPE,
                request = request,
            ),
        )
    }
}

@Component
class ProblemDetailAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        response.writeProblem(
            objectMapper = objectMapper,
            problem = apiProblem(
                status = HttpStatus.FORBIDDEN,
                title = "Access denied",
                detail = "You do not have permission to access this resource.",
                type = FORBIDDEN_TYPE,
                request = request,
            ),
        )
    }
}

private fun apiProblem(
    status: HttpStatus,
    title: String,
    detail: String,
    type: String,
    request: HttpServletRequest,
): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply {
    this.title = title
    this.type = URI.create(type)
    this.instance = URI.create(request.requestURI)
}

private fun HttpServletResponse.writeProblem(
    objectMapper: ObjectMapper,
    problem: ProblemDetail,
) {
    status = problem.status
    contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    characterEncoding = Charsets.UTF_8.name()
    objectMapper.writeValue(writer, problem)
}
