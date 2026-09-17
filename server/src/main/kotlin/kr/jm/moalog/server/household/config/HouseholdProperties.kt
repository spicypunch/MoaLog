package kr.jm.moalog.server.household.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

@ConfigurationProperties("moalog.household")
data class HouseholdProperties(
    val invitationTtl: Duration,
) {
    init {
        require(!invitationTtl.isZero && !invitationTtl.isNegative) { "Invitation TTL must be positive" }
    }
}

@Configuration
@EnableConfigurationProperties(HouseholdProperties::class)
class HouseholdConfig
