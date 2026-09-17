package kr.jm.moalog.server.system

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String)

data class InfoResponse(
    val name: String,
    val version: String,
)

@RestController
@RequestMapping("/api/system")
class SystemController {
    @GetMapping("/health")
    fun health() = HealthResponse(status = "UP")

    @GetMapping("/info")
    fun info() = InfoResponse(name = "MoaLog Server", version = "0.1.0")

}
