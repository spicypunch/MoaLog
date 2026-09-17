package kr.jm.moalog.server

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.jm.moalog.core.contracts.HouseholdCreateRequest
import kr.jm.moalog.core.contracts.LedgerMemberInputDto
import kr.jm.moalog.core.contracts.ClientPlatform
import kr.jm.moalog.core.contracts.SyncEntityType
import kr.jm.moalog.core.contracts.SyncMutationDto
import kr.jm.moalog.core.contracts.SyncOperation
import kr.jm.moalog.core.contracts.SyncPushRequest
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.server.auth.infrastructure.UserEntity
import kr.jm.moalog.server.auth.infrastructure.UserRepository
import kr.jm.moalog.server.auth.infrastructure.AuthSessionEntity
import kr.jm.moalog.server.auth.infrastructure.AuthSessionRepository
import kr.jm.moalog.server.auth.application.AuthTokenService
import kr.jm.moalog.server.household.application.HouseholdService
import kr.jm.moalog.server.sync.infrastructure.ProcessedSyncMutationRepository
import kr.jm.moalog.server.sync.infrastructure.SyncChangeRepository
import kr.jm.moalog.server.sync.web.MAX_SYNC_PUSH_REQUEST_BYTES
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncFlowIntegrationTest(
    @param:Autowired private val mockMvc: MockMvc,
    @param:Autowired private val objectMapper: ObjectMapper,
    @param:Autowired private val users: UserRepository,
    @param:Autowired private val sessions: AuthSessionRepository,
    @param:Autowired private val householdService: HouseholdService,
    @param:Autowired private val changes: SyncChangeRepository,
    @param:Autowired private val processed: ProcessedSyncMutationRepository,
) {
    @Test
    fun `create update and delete produce ordered versioned changes`() {
        val owner = newUser("crud")
        val householdId = newHousehold(owner)
        val deviceId = uuid()
        val entityId = uuid()

        val created = push(owner, householdId, request(deviceId, mutation(entityId, payload = payload("name", "처음"))))
            .andExpect { status { isOk() } }.json()
        assertEquals(1, created["applied"][0]["version"].asInt())

        val updated = push(
            owner,
            householdId,
            request(deviceId, mutation(entityId, baseVersion = 1, payload = payload("name", "수정"))),
        ).andExpect { status { isOk() } }.json()
        assertEquals(2, updated["applied"][0]["version"].asInt())

        val deleted = push(
            owner,
            householdId,
            request(deviceId, mutation(entityId, operation = SyncOperation.DELETE, baseVersion = 2)),
        ).andExpect { status { isOk() } }.json()
        assertEquals(3, deleted["applied"][0]["version"].asInt())

        mockMvc.get("/api/households/$householdId/sync/pull?cursor=0&limit=10") {
            with(jwtFor(owner))
        }.andExpect {
            status { isOk() }
            jsonPath("$.changes.length()") { value(3) }
            jsonPath("$.changes[0].operation") { value("upsert") }
            jsonPath("$.changes[0].version") { value(1) }
            jsonPath("$.changes[1].version") { value(2) }
            jsonPath("$.changes[2].operation") { value("delete") }
            jsonPath("$.changes[2].payload") { doesNotExist() }
            jsonPath("$.changes[2].version") { value(3) }
            jsonPath("$.hasMore") { value(false) }
        }
    }

    @Test
    fun `deleted entity can be resurrected from the matching tombstone version`() {
        val owner = newUser("tombstone")
        val householdId = newHousehold(owner)
        val deviceId = uuid()
        val entityId = uuid()

        push(owner, householdId, request(deviceId, mutation(entityId, payload = payload("v", 1))))
            .andExpect { status { isOk() } }
        push(owner, householdId, request(deviceId, mutation(entityId, SyncOperation.DELETE, baseVersion = 1)))
            .andExpect { status { isOk() } }
        push(owner, householdId, request(deviceId, mutation(entityId, baseVersion = 2, payload = payload("v", 2))))
            .andExpect {
                status { isOk() }
                jsonPath("$.applied[0].version") { value(3) }
            }

        assertEquals(3, changes.findByHouseholdIdAndCursorGreaterThanOrderByCursorAsc(
            householdId,
            0,
            org.springframework.data.domain.PageRequest.of(0, 10),
        ).size)
    }

    @Test
    fun `push device must match the authenticated session`() {
        val owner = newUser("device-binding")
        val householdId = newHousehold(owner)
        val session = newSession(owner, UUID.randomUUID())
        val request = request(uuid(), mutation(uuid(), payload = payload("v", 1)))

        mockMvc.post("/api/households/$householdId/sync/push") {
            with(jwtFor(owner, session.id))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(request)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-device-mismatch") }
        }
        mockMvc.post("/api/households/$householdId/sync/push") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(request)
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-device-mismatch") }
        }
        assertEquals(0, changes.findCurrentCursor(householdId) ?: 0)
    }

    @Test
    fun `all product payloads enforce their schema and relationships`() {
        val owner = newUser("typed-payloads")
        val householdId = newHousehold(owner)
        val deviceId = uuid()
        val catalogId = uuid()
        val monthItemId = uuid()
        val expenseCategoryId = uuid()
        val salaryCategoryId = uuid()
        val deductedCategoryId = uuid()
        val salaryChildId = uuid()
        val assetId = uuid()
        val mutations = listOf(
            mutation(catalogId, payload = buildJsonObject {
                put("type", "income"); put("classification", "월급"); put("name", "급여"); put("ownerMemberOrder", 0); put("displayOrder", 0)
            }, entityType = SyncEntityType.PLAN_CATALOG_ITEM),
            mutation(monthItemId, payload = buildJsonObject {
                put("catalogId", catalogId.value); put("attributionMonth", yearMonth(2026, 9)); put("amountWon", 3_000_000); put("status", "confirmed")
            }, entityType = SyncEntityType.MONTHLY_PLAN_ITEM),
            mutation(expenseCategoryId, payload = buildJsonObject {
                put("name", "식비"); put("displayOrder", 0)
            }, entityType = SyncEntityType.EXPENSE_CATEGORY),
            mutation(uuid(), payload = buildJsonObject {
                put("categoryId", expenseCategoryId.value); put("categoryName", "식비"); put("attributionMonth", yearMonth(2026, 9)); put("amountWon", 12_000); put("overspent", false)
            }, entityType = SyncEntityType.EXPENSE_RECORD),
            mutation(uuid(), payload = buildJsonObject {
                put("attributionMonth", yearMonth(2026, 9)); put("memberOrder", 0); put("amountWon", 3_000_000)
            }, entityType = SyncEntityType.SALARY_INCOME),
            mutation(salaryCategoryId, payload = remainingSalaryCategoryPayload("생활비", 0), entityType = SyncEntityType.SALARY_ALLOCATION_CATEGORY),
            mutation(deductedCategoryId, payload = salaryCategoryPayload("저축", 1), entityType = SyncEntityType.SALARY_ALLOCATION_CATEGORY),
            mutation(uuid(), payload = buildJsonObject {
                put("categoryId", salaryCategoryId.value); put("deductedCategoryId", deductedCategoryId.value)
            }, entityType = SyncEntityType.SALARY_ALLOCATION_DEDUCTION),
            mutation(salaryChildId, payload = buildJsonObject {
                put("categoryId", salaryCategoryId.value); put("attributionMonth", yearMonth(2026, 9)); put("name", "장보기"); put("amountWon", 300_000); put("displayOrder", 0)
            }, entityType = SyncEntityType.SALARY_ALLOCATION_CHILD),
            mutation(uuid(), payload = buildJsonObject {
                put("childId", salaryChildId.value); put("attributionMonth", yearMonth(2026, 9)); put("name", "마트"); put("amountWon", 200_000); put("displayOrder", 0)
            }, entityType = SyncEntityType.SALARY_ALLOCATION_GRANDCHILD),
            mutation(uuid(), payload = buildJsonObject {
                put("attributionMonth", yearMonth(2026, 9)); put("payerMemberOrder", 0); put("name", "보험료"); put("amountWon", 100_000); put("displayOrder", 0)
            }, entityType = SyncEntityType.FIXED_COST_ITEM),
            mutation(assetId, payload = buildJsonObject {
                put("name", "비상금"); put("type", "cash"); put("ownerMemberOrder", 0); put("kind", "ordinary")
            }, entityType = SyncEntityType.ASSET),
            mutation(uuid(), payload = buildJsonObject {
                put("assetId", assetId.value); put("valuationMonth", yearMonth(2026, 9)); put("amountWon", 1_000_000)
            }, entityType = SyncEntityType.ASSET_VALUATION),
            mutation(uuid(), payload = buildJsonObject {
                put("assetId", assetId.value); put("startMonth", yearMonth(2026, 9)); put("durationMonths", 12); put("baseAmountWon", 1_000_000); put("monthlyIncreaseWon", 100_000)
            }, entityType = SyncEntityType.ASSET_GROWTH_RULE),
            mutation(uuid(), payload = buildJsonObject {
                put("billMonth", yearMonth(2026, 9)); put("entries", maintenanceEntries())
            }, entityType = SyncEntityType.MAINTENANCE_FEE_MONTH),
        )

        push(owner, householdId, SyncPushRequest(deviceId, mutations)).andExpect {
            status { isOk() }
            jsonPath("$.applied.length()") { value(mutations.size) }
        }

        push(owner, householdId, request(deviceId, mutation(uuid(), payload = buildJsonObject {
            put("name", "잘못된 분류"); put("displayOrder", 0); put("unexpected", true)
        }))).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-payload-invalid") }
        }
        push(owner, householdId, request(deviceId, mutation(
            catalogId,
            SyncOperation.DELETE,
            baseVersion = 1,
            entityType = SyncEntityType.PLAN_CATALOG_ITEM,
        )))
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.type") { value("urn:moalog:problem:sync-payload-invalid") }
            }
    }

    @Test
    fun `same canonical mutation replays but changed content or device conflicts`() {
        val owner = newUser("idempotency")
        val householdId = newHousehold(owner)
        val deviceId = uuid()
        val mutationId = uuid()
        val entityId = uuid()
        val ordered = buildJsonObject { put("name", "식비"); put("displayOrder", 2) }
        val reordered = buildJsonObject { put("displayOrder", 2); put("name", "식비") }
        val original = request(deviceId, mutation(entityId, payload = ordered, mutationId = mutationId))

        val first = push(owner, householdId, original).andExpect { status { isOk() } }.json()
        val replay = push(
            owner,
            householdId,
            request(deviceId, mutation(entityId, payload = reordered, mutationId = mutationId)),
        ).andExpect { status { isOk() } }.json()
        assertEquals(first["applied"], replay["applied"])
        assertEquals(first["currentCursor"], replay["currentCursor"])
        assertEquals(1, changes.findByHouseholdIdAndCursorGreaterThanOrderByCursorAsc(
            householdId,
            0,
            org.springframework.data.domain.PageRequest.of(0, 10),
        ).size)

        push(
            owner,
            householdId,
            request(deviceId, mutation(entityId, payload = payload("alpha", 9), mutationId = mutationId)),
        ).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-idempotency-conflict") }
        }
        push(
            owner,
            householdId,
            request(uuid(), mutation(entityId, payload = ordered, mutationId = mutationId)),
        ).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-idempotency-conflict") }
        }
        assertEquals(deviceId.value, processed.findByHouseholdIdAndMutationId(
            householdId,
            UUID.fromString(mutationId.value),
        )!!.deviceId.toString())
    }

    @Test
    fun `stale mutation rolls back every earlier mutation in the batch`() {
        val owner = newUser("rollback")
        val householdId = newHousehold(owner)
        val deviceId = uuid()
        val entityId = uuid()
        val initial = push(owner, householdId, request(deviceId, mutation(entityId, payload = payload("v", 1))))
            .andExpect { status { isOk() } }.json()
        val initialCursor = initial["currentCursor"].asLong()

        push(
            owner,
            householdId,
            request(
                deviceId,
                mutation(entityId, baseVersion = 1, payload = payload("v", 2)),
                mutation(uuid(), operation = SyncOperation.DELETE, baseVersion = 1),
            ),
        ).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-version-conflict") }
        }

        mockMvc.get("/api/households/$householdId/sync/pull?cursor=$initialCursor&limit=10") {
            with(jwtFor(owner))
        }.andExpect {
            status { isOk() }
            jsonPath("$.changes.length()") { value(0) }
            jsonPath("$.nextCursor") { value(initialCursor) }
            jsonPath("$.hasMore") { value(false) }
        }
        push(
            owner,
            householdId,
            request(deviceId, mutation(entityId, baseVersion = 1, payload = payload("v", 3))),
        ).andExpect {
            status { isOk() }
            jsonPath("$.applied[0].version") { value(2) }
        }
        push(
            owner,
            householdId,
            request(deviceId, mutation(entityId, baseVersion = 1, payload = payload("v", 4))),
        ).andExpect {
            status { isConflict() }
            jsonPath("$.type") { value("urn:moalog:problem:sync-version-conflict") }
        }
    }

    @Test
    fun `pull paginates accurately and isolates households`() {
        val owner = newUser("pages")
        val firstHousehold = newHousehold(owner)
        val secondHousehold = newHousehold(owner)
        val deviceId = uuid()
        val firstHouseholdEntityIds = List(3) { uuid() }
        firstHouseholdEntityIds.forEachIndexed { index, entityId ->
            push(owner, firstHousehold, request(deviceId, mutation(entityId, payload = payload("index", index))))
                .andExpect { status { isOk() } }
        }
        val secondHouseholdEntityId = uuid()
        push(owner, secondHousehold, request(deviceId, mutation(secondHouseholdEntityId, payload = payload("secret", true))))
            .andExpect { status { isOk() } }

        val firstPage = pull(owner, firstHousehold, cursor = 0, limit = 2)
            .andExpect { status { isOk() } }.json()
        assertEquals(2, firstPage["changes"].size())
        assertTrue(firstPage["hasMore"].asBoolean())
        val firstCursor = firstPage["changes"][0]["cursor"].asLong()
        val nextCursor = firstPage["nextCursor"].asLong()
        assertTrue(nextCursor > firstCursor)

        val secondPage = pull(owner, firstHousehold, nextCursor, 2)
            .andExpect { status { isOk() } }.json()
        assertEquals(1, secondPage["changes"].size())
        assertEquals(false, secondPage["hasMore"].asBoolean())
        val pulledEntityIds = (firstPage["changes"].toList() + secondPage["changes"].toList())
            .map { it["entityId"].asText() }
            .toSet()
        assertEquals(firstHouseholdEntityIds.map { it.value }.toSet(), pulledEntityIds)
        assertTrue(secondHouseholdEntityId.value !in pulledEntityIds)
        val terminalCursor = secondPage["nextCursor"].asLong()

        pull(owner, firstHousehold, terminalCursor, 2).andExpect {
            status { isOk() }
            jsonPath("$.changes.length()") { value(0) }
            jsonPath("$.nextCursor") { value(terminalCursor) }
            jsonPath("$.hasMore") { value(false) }
        }
    }

    @Test
    fun `non-member sees not found and generic structural mutations are rejected`() {
        val owner = newUser("scope-owner")
        val outsider = newUser("scope-outsider")
        val householdId = newHousehold(owner)
        val request = request(uuid(), mutation(uuid(), payload = payload("safe", true)))

        push(outsider, householdId, request).andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("urn:moalog:problem:household-not-found") }
        }
        pull(outsider, householdId, 0, 10).andExpect {
            status { isNotFound() }
            jsonPath("$.type") { value("urn:moalog:problem:household-not-found") }
        }
        listOf(
            SyncEntityType.HOUSEHOLD,
            SyncEntityType.LEDGER_MEMBER,
            SyncEntityType.ANNUAL_SAVINGS_TARGET,
        ).forEach { structuralType ->
            push(
                owner,
                householdId,
                request(
                    uuid(),
                    mutation(uuid(), entityType = structuralType, payload = payload("name", "bypass")),
                ),
            ).andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.type") { value("urn:moalog:problem:sync-entity-type-unsupported") }
            }
        }
        assertEquals(0, changes.findCurrentCursor(householdId) ?: 0)
    }

    @Test
    fun `push and pull limits are enforced and endpoints require authentication`() {
        val owner = newUser("limits")
        val householdId = newHousehold(owner)
        val mutationJson = """{"mutationId":"${uuid().value}","entityType":"expense_record","entityId":"${uuid().value}","operation":"upsert","baseVersion":null,"payload":{"v":1}}"""
        val tooMany = (1..101).joinToString(",") { index ->
            mutationJson.replace(Regex("\"mutationId\":\"[^\"]+"), "\"mutationId\":\"${UUID.nameUUIDFromBytes(index.toString().toByteArray())}")
        }
        mockMvc.post("/api/households/$householdId/sync/push") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = """{"deviceId":"${uuid().value}","mutations":[$tooMany]}"""
        }.andExpect { status { isBadRequest() } }

        val oversized = "x".repeat(65_537)
        mockMvc.post("/api/households/$householdId/sync/push") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = """{"deviceId":"${uuid().value}","mutations":[{"mutationId":"${uuid().value}","entityType":"expense_record","entityId":"${uuid().value}","operation":"upsert","baseVersion":null,"payload":{"v":"$oversized"}}]}"""
        }.andExpect { status { isBadRequest() } }

        mockMvc.post("/api/households/$householdId/sync/push") {
            with(jwtFor(owner))
            contentType = MediaType.APPLICATION_JSON
            content = ByteArray(MAX_SYNC_PUSH_REQUEST_BYTES + 1) { 'x'.code.toByte() }
        }.andExpect {
            status { isPayloadTooLarge() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.type") { value("urn:moalog:problem:sync-request-too-large") }
        }

        mockMvc.post("/api/households/$householdId/sync/push") {
            contentType = MediaType.APPLICATION_JSON
            content = ByteArray(MAX_SYNC_PUSH_REQUEST_BYTES + 1) { 'x'.code.toByte() }
        }.andExpect { status { isUnauthorized() } }

        pull(owner, householdId, 0, 0).andExpect { status { isBadRequest() } }
        pull(owner, householdId, 0, 501).andExpect { status { isBadRequest() } }
        mockMvc.post("/api/households/$householdId/sync/push") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(request(uuid(), mutation(uuid(), payload = payload("v", 1))))
        }.andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/households/$householdId/sync/pull").andExpect { status { isUnauthorized() } }
    }

    private fun newUser(label: String): UserEntity {
        val now = Instant.now()
        return users.saveAndFlush(
            UserEntity(
                displayName = label,
                email = "$label-${UUID.randomUUID()}@example.test",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun newHousehold(owner: UserEntity): UUID = householdService.create(
        owner.id,
        HouseholdCreateRequest(
            name = "동기화 가계부",
            baseYear = 2026,
            creatorMemberOrder = 0,
            members = listOf(LedgerMemberInputDto("나", 0), LedgerMemberInputDto("배우자", 1)),
        ),
    ).householdId.value.let(UUID::fromString)

    private fun newSession(user: UserEntity, deviceId: UUID): AuthSessionEntity {
        val now = Instant.now()
        return sessions.saveAndFlush(
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
    }

    private fun request(deviceId: UuidString, vararg mutations: SyncMutationDto) =
        SyncPushRequest(deviceId, mutations.toList())

    private fun mutation(
        entityId: UuidString,
        operation: SyncOperation = SyncOperation.UPSERT,
        baseVersion: Long? = null,
        payload: JsonObject? = null,
        mutationId: UuidString = uuid(),
        entityType: SyncEntityType = SyncEntityType.EXPENSE_CATEGORY,
    ) = SyncMutationDto(mutationId, entityType, entityId, operation, baseVersion, payload)

    private fun payload(key: String, value: String) = buildJsonObject {
        put("name", "$key-$value")
        put("displayOrder", 0)
    }
    private fun payload(key: String, value: Int) = buildJsonObject {
        put("name", "$key-$value")
        put("displayOrder", value.coerceAtLeast(0))
    }
    private fun payload(key: String, value: Boolean) = buildJsonObject {
        put("name", key)
        put("displayOrder", 0)
        put("archived", value)
    }
    private fun yearMonth(year: Int, month: Int) = buildJsonObject {
        put("year", year)
        put("month", month)
    }
    private fun salaryCategoryPayload(name: String, displayOrder: Int) = buildJsonObject {
        put("attributionMonth", yearMonth(2026, 9))
        put("name", name)
        put("sourceMemberOrder", 0)
        put("method", "fixed_amount")
        put("amountWon", 500_000)
        put("displayOrder", displayOrder)
    }
    private fun remainingSalaryCategoryPayload(name: String, displayOrder: Int) = buildJsonObject {
        put("attributionMonth", yearMonth(2026, 9))
        put("name", name)
        put("sourceMemberOrder", 0)
        put("method", "remaining_from_source")
        put("displayOrder", displayOrder)
    }
    private fun maintenanceEntries() = buildJsonArray {
        listOf(
            "general_management", "cleaning", "disinfection", "elevator_maintenance",
            "repair_maintenance", "long_term_repair_reserve", "building_insurance",
            "security_service", "management_commission", "residents_committee",
            "election_committee", "household_electricity", "common_electricity",
            "elevator_electricity", "tv_license", "household_water", "household_heating",
            "basic_heating", "household_hot_water", "deduction", "household_waste",
        ).forEachIndexed { index, key ->
            add(buildJsonObject {
                put("key", key)
                put("amountWon", if (key == "deduction") -1_000 else index * 1_000)
            })
        }
    }
    private fun uuid() = UuidString(UUID.randomUUID().toString())

    private fun push(
        user: UserEntity,
        householdId: UUID,
        request: SyncPushRequest,
    ): ResultActionsDsl {
        val session = newSession(user, UUID.fromString(request.deviceId.value))
        return mockMvc.post("/api/households/$householdId/sync/push") {
        with(jwtFor(user, session.id))
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsBytes(request)
        }
    }

    private fun pull(user: UserEntity, householdId: UUID, cursor: Long, limit: Int): ResultActionsDsl =
        mockMvc.get("/api/households/$householdId/sync/pull?cursor=$cursor&limit=$limit") {
            with(jwtFor(user))
        }

    private fun ResultActionsDsl.json(): JsonNode =
        andReturn().response.contentAsByteArray.let(objectMapper::readTree)

    private fun jwtFor(user: UserEntity, sessionId: UUID? = null) = jwt().jwt {
        it.subject(user.id.toString())
        if (sessionId != null) it.claim(AuthTokenService.SESSION_ID_CLAIM, sessionId.toString())
    }
}
