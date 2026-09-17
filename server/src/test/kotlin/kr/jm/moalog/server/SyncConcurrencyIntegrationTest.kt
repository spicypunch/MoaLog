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
import kr.jm.moalog.server.sync.infrastructure.ProcessedSyncMutationRepository
import kr.jm.moalog.server.sync.infrastructure.SyncChangeRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
@ActiveProfiles("test")
class SyncConcurrencyIntegrationTest(
    @param:Autowired private val syncService: SyncService,
    @param:Autowired private val users: UserRepository,
    @param:Autowired private val sessions: AuthSessionRepository,
    @param:Autowired private val householdService: HouseholdService,
    @param:Autowired private val changes: SyncChangeRepository,
    @param:Autowired private val processed: ProcessedSyncMutationRepository,
) {
    @Test
    fun `only one concurrent mutation with the same base version succeeds`() {
        val context = newContext("concurrent-version")
        val entityId = uuid()
        syncService.push(
            context.user.id,
            context.session.id,
            context.householdId,
            request(context.deviceId, mutation(entityId, payloadValue = 0)),
        )
        val firstMutationId = uuid()
        val secondMutationId = uuid()
        val results = concurrently(
            { syncService.push(context.user.id, context.session.id, context.householdId, request(context.deviceId, mutation(entityId, 1, firstMutationId, 1))) },
            { syncService.push(context.user.id, context.session.id, context.householdId, request(context.deviceId, mutation(entityId, 1, secondMutationId, 2))) },
        )

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is SyncVersionConflictException })
        assertEquals(2, changeCount(context.householdId))
        assertEquals(1, listOf(firstMutationId, secondMutationId).count {
            processed.findByHouseholdIdAndMutationId(context.householdId, UUID.fromString(it.value)) != null
        })
    }

    @Test
    fun `concurrent replay of the same mutation produces one change and identical result`() {
        val context = newContext("concurrent-idempotency")
        val mutationId = uuid()
        val request = request(context.deviceId, mutation(uuid(), mutationId = mutationId, payloadValue = 1))
        val results = concurrently(
            { syncService.push(context.user.id, context.session.id, context.householdId, request) },
            { syncService.push(context.user.id, context.session.id, context.householdId, request) },
        )

        assertTrue(results.all { it.isSuccess })
        assertEquals(results[0].getOrThrow(), results[1].getOrThrow())
        assertEquals(1, changeCount(context.householdId))
        assertTrue(processed.findByHouseholdIdAndMutationId(context.householdId, UUID.fromString(mutationId.value)) != null)
    }

    private fun concurrently(
        first: () -> Any,
        second: () -> Any,
    ): List<Result<Any>> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val futures = listOf(first, second).map { action ->
                executor.submit(Callable {
                    ready.countDown()
                    start.await()
                    runCatching(action)
                })
            }
            ready.await()
            start.countDown()
            futures.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun newContext(label: String): Context {
        val now = Instant.now()
        val user = users.saveAndFlush(
            UserEntity(
                displayName = label,
                email = "$label-${UUID.randomUUID()}@example.test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val householdId = UUID.fromString(householdService.create(
            user.id,
            HouseholdCreateRequest(
                name = "동시성 테스트",
                baseYear = 2026,
                creatorMemberOrder = 0,
                members = listOf(LedgerMemberInputDto("나", 0), LedgerMemberInputDto("배우자", 1)),
            ),
        ).householdId.value)
        val deviceId = uuid()
        val session = sessions.saveAndFlush(
            AuthSessionEntity(
                user = user,
                deviceId = UUID.fromString(deviceId.value),
                platform = ClientPlatform.ANDROID,
                appVersion = "test",
                createdAt = now,
                lastUsedAt = now,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        return Context(user, session, householdId, deviceId)
    }

    private fun request(deviceId: UuidString, mutation: SyncMutationDto) = SyncPushRequest(deviceId, listOf(mutation))

    private fun changeCount(householdId: UUID): Int =
        changes.findByHouseholdIdAndCursorGreaterThanOrderByCursorAsc(
            householdId,
            0,
            PageRequest.of(0, 10),
        ).size

    private fun mutation(
        entityId: UuidString,
        baseVersion: Long? = null,
        mutationId: UuidString = uuid(),
        payloadValue: Int,
    ) = SyncMutationDto(
        mutationId = mutationId,
        entityType = SyncEntityType.EXPENSE_CATEGORY,
        entityId = entityId,
        operation = SyncOperation.UPSERT,
        baseVersion = baseVersion,
        payload = buildJsonObject {
            put("name", "category-$payloadValue")
            put("displayOrder", payloadValue)
        },
    )

    private fun uuid() = UuidString(UUID.randomUUID().toString())

    private data class Context(
        val user: UserEntity,
        val session: AuthSessionEntity,
        val householdId: UUID,
        val deviceId: UuidString,
    )
}
