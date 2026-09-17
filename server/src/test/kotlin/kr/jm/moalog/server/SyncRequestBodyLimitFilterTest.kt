package kr.jm.moalog.server

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import kr.jm.moalog.server.sync.web.MAX_SYNC_PUSH_REQUEST_BYTES
import kr.jm.moalog.server.sync.web.SyncRequestBodyLimitFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayInputStream

class SyncRequestBodyLimitFilterTest {
    @Test
    fun `unknown content length is still rejected before the filter chain`() {
        val content = ByteArray(MAX_SYNC_PUSH_REQUEST_BYTES + 1) { 'x'.code.toByte() }
        val baseRequest = MockHttpServletRequest("POST", "/api/households/${java.util.UUID.randomUUID()}/sync/push")
        val request = UnknownLengthRequest(baseRequest, content)
        val response = MockHttpServletResponse()
        var continued = false
        val chain = FilterChain { _, _ -> continued = true }

        SyncRequestBodyLimitFilter(ObjectMapper()).doFilter(request, response, chain)

        assertEquals(413, response.status)
        assertEquals("application/problem+json", response.contentType)
        assertFalse(continued)
        assertEquals(
            "urn:moalog:problem:sync-request-too-large",
            ObjectMapper().readTree(response.contentAsByteArray)["type"].asText(),
        )
    }

    private class UnknownLengthRequest(
        request: HttpServletRequest,
        private val content: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getContentLength(): Int = -1
        override fun getContentLengthLong(): Long = -1
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
}
