package kr.jm.moalog.server

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kr.jm.moalog.server.web.ApiExceptionHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

class ApiExceptionHandlerTest {
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mockMvc = MockMvcBuilders
            .standaloneSetup(ValidationTestController())
            .setControllerAdvice(ApiExceptionHandler())
            .setValidator(validator)
            .build()
    }

    @Test
    fun `invalid body uses problem detail response`() {
        mockMvc.post("/__test/validation") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"label":" "}"""
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("urn:moalog:problem:validation") }
            jsonPath("$.title") { value("Invalid request") }
            jsonPath("$.errors[0].field") { value("label") }
        }
    }
}

data class ValidationTestRequest(
    @field:NotBlank
    @field:Size(max = 40)
    val label: String,
)

@RestController
@RequestMapping("/__test")
private class ValidationTestController {
    @PostMapping("/validation")
    fun validate(@Valid @RequestBody request: ValidationTestRequest) = request
}
