package kr.jm.moalog.server

import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.infrastructure.AuthSessionEntity
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.household.application.HouseholdService
import kr.jm.moalog.server.sync.application.SyncService
import kr.jm.moalog.server.sync.application.SyncVersionConflictException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.jdbc.JdbcTestUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@Tag("postgres")
@Testcontainers
@SpringBootTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "moalog.auth.access-token.issuer=https://test.moalog.local",
        "moalog.auth.access-token.audience=moalog-test",
        "moalog.auth.access-token.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "moalog.auth.google-audience=google-test-client",
        "moalog.auth.apple-audience=apple-test-client",
    ],
)
class PostgresMigrationIntegrationTest(
    @param:Autowired private val jdbcTemplate: JdbcTemplate,
    @param:Autowired private val syncService: SyncService,
    @param:Autowired private val users: UserRepository,
    @param:Autowired private val sessions: AuthSessionRepository,
    @param:Autowired private val householdService: HouseholdService,
) {
    @Test
    fun `flyway migrates the moalog schema`() {
        val schemaExists = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.schemata where schema_name = 'moalog'",
            Int::class.java,
        )
        assertEquals(1, schemaExists ?: 0)
        val appliedVersions = jdbcTemplate.queryForList(
            """
            select version
            from moalog.flyway_schema_history
            where version is not null and success = true
            order by installed_rank
            """.trimIndent(),
            String::class.java,
        )
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), appliedVersions)
        val authSessionTableExists = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.tables where table_schema = 'moalog' and table_name = 'auth_sessions'",
            Int::class.java,
        )
        assertEquals(1, authSessionTableExists ?: 0)
    }

    @Test
    fun `postgres serializes competing sync writes for one household`() {
        val now = Instant.now()
        val user = users.saveAndFlush(
            UserEntity(
                displayName = "postgres-sync",
                email = "postgres-sync-${UUID.randomUUID()}@example.test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val householdId = UUID.fromString(householdService.create(
            user.id,
            HouseholdCreateRequest(
                name = "PostgreSQL 동시성",
                baseYear = 2026,
                creatorMemberOrder = 0,
                members = listOf(LedgerMemberInputDto("나", 0), LedgerMemberInputDto("배우자", 1)),
            ),
        ).householdId.value)
        val deviceId = UUID.randomUUID()
        val session = sessions.saveAndFlush(
            AuthSessionEntity(
                user = user,
                deviceId = deviceId,
                platform = ClientPlatform.ANDROID,
                appVersion = "test",
                createdAt = now,
                lastUsedAt = now,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        val entityId = UUID.randomUUID()
        syncService.push(user.id, session.id, householdId, request(deviceId, entityId, null, 0))

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val results = try {
            listOf(1, 2).map { value ->
                executor.submit(Callable {
                    ready.countDown()
                    start.await()
                    runCatching { syncService.push(user.id, session.id, householdId, request(deviceId, entityId, 1, value)) }
                })
            }.also {
                ready.await()
                start.countDown()
            }.map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is SyncVersionConflictException })
        val versions = jdbcTemplate.queryForList(
            "select entity_version from moalog.sync_entity_snapshots where household_id = ? and entity_id = ?",
            Long::class.java,
            householdId,
            entityId,
        )
        assertEquals(listOf(2L), versions)
        assertTrue(JdbcTestUtils.countRowsInTableWhere(
            jdbcTemplate,
            "moalog.sync_change_log",
            "household_id = '$householdId'",
        ) == 2)

        val replayEntityId = UUID.randomUUID()
        val replayMutationId = UUID.randomUUID()
        val replayRequest = request(deviceId, replayEntityId, null, 9, replayMutationId)
        val replayReady = CountDownLatch(2)
        val replayStart = CountDownLatch(1)
        val replayExecutor = Executors.newFixedThreadPool(2)
        val replayResults = try {
            List(2) {
                replayExecutor.submit(Callable {
                    replayReady.countDown()
                    replayStart.await()
                    runCatching { syncService.push(user.id, session.id, householdId, replayRequest) }
                })
            }.also {
                replayReady.await()
                replayStart.countDown()
            }.map { it.get() }
        } finally {
            replayExecutor.shutdownNow()
        }
        assertTrue(replayResults.all { it.isSuccess })
        assertEquals(replayResults[0].getOrThrow(), replayResults[1].getOrThrow())
        assertEquals(
            1,
            JdbcTestUtils.countRowsInTableWhere(
                jdbcTemplate,
                "moalog.sync_change_log",
                "household_id = '$householdId' and entity_id = '$replayEntityId'",
            ),
        )
    }

    private fun request(
        deviceId: UUID,
        entityId: UUID,
        baseVersion: Long?,
        value: Int,
        mutationId: UUID = UUID.randomUUID(),
    ) = SyncPushRequest(
        deviceId = UuidString(deviceId.toString()),
        mutations = listOf(
            SyncMutationDto(
                mutationId = UuidString(mutationId.toString()),
                entityType = SyncEntityType.EXPENSE_CATEGORY,
                entityId = UuidString(entityId.toString()),
                operation = SyncOperation.UPSERT,
                baseVersion = baseVersion,
                payload = buildJsonObject {
                    put("name", "category-$value")
                    put("displayOrder", value)
                },
            ),
        ),
    )

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
