package kr.jm.moalog.server.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig(
    private val authenticationEntryPoint: ProblemDetailAuthenticationEntryPoint,
    private val accessDeniedHandler: ProblemDetailAccessDeniedHandler,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .requestCache { it.disable() }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .logout { it.disable() }
        .oauth2ResourceServer {
            it.jwt { }
                .authenticationEntryPoint(authenticationEntryPoint)
        }
        .exceptionHandling {
            it.authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler)
        }
        .authorizeHttpRequests {
            it.requestMatchers(
                HttpMethod.GET,
                "/api/system/health",
                "/api/system/info",
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/info",
            ).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/challenge", "/api/auth/exchange", "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/auth/me").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/auth/sessions/{sessionId}").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/households").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/households").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/households/{householdId}").authenticated()
                .requestMatchers(HttpMethod.PATCH, "/api/households/{householdId}").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/households/{householdId}/sync/push").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/households/{householdId}/sync/pull").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/households/{householdId}/invitations").authenticated()
                .requestMatchers(
                    HttpMethod.DELETE,
                    "/api/households/{householdId}/invitations/{invitationId}",
                ).authenticated()
                .requestMatchers(HttpMethod.POST, "/api/household-invitations/accept").authenticated()
                .anyRequest().denyAll()
        }
        .build()
}
