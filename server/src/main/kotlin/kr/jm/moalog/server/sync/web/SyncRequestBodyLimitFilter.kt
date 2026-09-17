package kr.jm.moalog.server.sync.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.annotation.Order
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

const val MAX_SYNC_PUSH_REQUEST_BYTES: Int = 7 * 1024 * 1024

@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
class SyncRequestBodyLimitFilter(
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || !SYNC_PUSH_PATH.matches(request.requestURI)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (request.contentLengthLong > MAX_SYNC_PUSH_REQUEST_BYTES) {
            writeTooLarge(response, request.requestURI)
            return
        }

        val content = readBounded(request)
        if (content == null) {
            writeTooLarge(response, request.requestURI)
            return
        }
        filterChain.doFilter(CachedBodyRequest(request, content), response)
    }

    private fun readBounded(request: HttpServletRequest): ByteArray? {
        val initialSize = request.contentLengthLong
            .coerceIn(0, 8 * 1024L)
            .toInt()
        val output = ByteArrayOutputStream(initialSize)
        val buffer = ByteArray(8 * 1024)
        var total = 0
        request.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_SYNC_PUSH_REQUEST_BYTES) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun writeTooLarge(response: HttpServletResponse, requestUri: String) {
        response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        val problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "The synchronization request body exceeds the allowed size.",
        ).apply {
            title = "Synchronization request too large"
            type = URI.create("urn:moalog:problem:sync-request-too-large")
            instance = URI.create(requestUri)
        }
        objectMapper.writeValue(response.outputStream, problem)
    }

    private class CachedBodyRequest(
        request: HttpServletRequest,
        private val content: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getContentLength(): Int = content.size
        override fun getContentLengthLong(): Long = content.size.toLong()
        override fun getReader(): BufferedReader = BufferedReader(
            InputStreamReader(
                inputStream,
                characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8,
            ),
        )
        override fun getInputStream(): ServletInputStream {
            val input = ByteArrayInputStream(content)
            return object : ServletInputStream() {
                override fun read(): Int = input.read()
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = input.read(bytes, offset, length)
                override fun isFinished(): Boolean = input.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(readListener: ReadListener?) = Unit
            }
        }
    }

    private companion object {
        val SYNC_PUSH_PATH = Regex(".*/api/households/[0-9a-fA-F-]{36}/sync/push$")
    }
}
