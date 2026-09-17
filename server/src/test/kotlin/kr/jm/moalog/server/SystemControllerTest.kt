package kr.jm.moalog.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemControllerTest(
    @param:Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `health endpoint is public and reports up`() {
        mockMvc.get("/api/system/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
    }

    @Test
    fun `actuator health endpoint is public`() {
        mockMvc.get("/actuator/health")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
    }

    @Test
    fun `non-system API is denied with problem detail`() {
        mockMvc.get("/api/system/not-allowlisted")
            .andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.type") { value("urn:moalog:problem:unauthorized") }
            }
    }
}
