package kr.jm.moalog.household

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kr.jm.moalog.auth.AuthSessionStatus
import kr.jm.moalog.core.contracts.UuidString
import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.core.network.MoaLogHouseholdApi
import kr.jm.moalog.core.network.MoaLogNetworkConfig
import kr.jm.moalog.core.network.createMoaLogHttpClient
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kr.jm.moalog.core.database.PendingHouseholdSettings
import kr.jm.moalog.core.database.PersistentSyncMutation
import kr.jm.moalog.core.database.SyncLocalStore
import kr.jm.moalog.core.database.SyncUuidFactory
import kr.jm.moalog.sync.SyncOperationGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MoaLogHouseholdSessionManagerTest {
    @Test
    fun restoreWhenSignedOutClearsState() = runTest {
        val manager = createManager()

        manager.restore(AuthSessionStatus.SIGNED_OUT)

        assertEquals(HouseholdSessionStatus.IDLE, manager.state.value.status)
        assertNull(manager.state.value.activeHousehold)
        assertNull(manager.state.value.invitation)
    }

    @Test
    fun restoreWithoutBoundHouseholdNeedsConnection() = runTest {
        val api = createHouseholdApi {
            when {
                it == "GET /api/households" -> HttpResponsePlan(HttpStatusCode.OK, LIST_ONE_JSON)
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }
        val manager = createManager(api = api, syncStore = FakeSyncLocalStore())

        manager.restore(AuthSessionStatus.SIGNED_IN)

        val state = manager.state.value
        assertEquals(HouseholdSessionStatus.NEEDS_CONNECTION, state.status)
        assertEquals(1, state.availableHouseholds.size)
        assertNull(state.activeHousehold)
    }

    @Test
    fun restoreFindsBoundHouseholdAndRefreshesIfPendingSettingsMatch() = runTest {
        val setup = LedgerSetup(
            ledgerName = "원래 가계부",
            members = listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
            baseYear = 2026,
            annualSavingsTargetWon = 100_000,
        )
        val setupRepository = FakeSetupRepository(setup)
        val store = FakeSyncLocalStore().apply {
            boundHousehold = householdId
            pendingSettings = PendingHouseholdSettings(householdId, 1L)
        }
        val api = createHouseholdApi {
            when {
                it == "GET /api/households" -> HttpResponsePlan(HttpStatusCode.OK, LIST_ONE_JSON)
                it == "GET /api/households/$householdId" -> HttpResponsePlan(HttpStatusCode.OK, DETAIL_ORIGINAL_JSON)
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }

        val manager = createManager(api = api, syncStore = store, setupRepository = setupRepository)
        manager.restore(AuthSessionStatus.SIGNED_IN)

        assertEquals(HouseholdSessionStatus.READY, manager.state.value.status)
        assertNotNull(manager.state.value.activeHousehold)
        assertEquals(1, store.clearPendingCalls)
    }

    @Test
    fun createFromLocalBindsHouseholdAndMovesReady() = runTest {
        val api = createHouseholdApi {
            when {
                it == "POST /api/households" -> HttpResponsePlan(HttpStatusCode.OK, DETAIL_ORIGINAL_JSON)
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }
        val store = FakeSyncLocalStore()
        val setup = LedgerSetup(
            ledgerName = "원래 가계부",
            members = listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
            baseYear = 2026,
            annualSavingsTargetWon = 100_000,
        )

        val manager = createManager(api = api, syncStore = store)
        manager.createFromLocal(setup, creatorMemberOrder = 0)

        val state = manager.state.value
        assertEquals(HouseholdSessionStatus.READY, state.status)
        assertEquals(householdId, store.boundHousehold)
        assertEquals(1, state.availableHouseholds.size)
        assertEquals("원래 가계부", state.availableHouseholds.single().name)
    }

    @Test
    fun connectExistingCallsApiAndPersistsSetup() = runTest {
        val setupRepository = FakeSetupRepository(LedgerSetup("local", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 0))
        val api = createHouseholdApi {
            when {
                it == "GET /api/households/$householdId" -> HttpResponsePlan(HttpStatusCode.OK, DETAIL_ORIGINAL_JSON)
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }

        val manager = createManager(api = api, setupRepository = setupRepository)
        manager.connectExisting(householdId)

        assertEquals(HouseholdSessionStatus.READY, manager.state.value.status)
        assertEquals("원래 가계부", manager.state.value.activeHousehold?.name)
        assertEquals(1, setupRepository.saveCalls)
    }

    @Test
    fun offlineLocalBoundUpdateMarksPendingAndShowsNotice() = runTest {
        val store = FakeSyncLocalStore().apply {
            boundHousehold = householdId
        }
        val setup = LedgerSetup("오프라인 갱신", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 0)
        val manager = createManager(syncStore = store, setupRepository = FakeSetupRepository(setup))

        manager.updateFromLocal(setup)

        assertEquals("인터넷에 연결되면 가계부 설정을 반영할게요", manager.state.value.errorMessage)
        assertEquals(1, store.markPendingCalls)
        assertEquals(1L, store.pendingSettings?.baseVersion)
    }

    @Test
    fun pendingOfflineSettingsUploadBeforeRemoteCanOverwriteLocalSetup() = runTest {
        val local = LedgerSetup(
            "오프라인 갱신",
            listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
            2026,
            0,
        )
        val setupRepository = FakeSetupRepository(local)
        val store = FakeSyncLocalStore().apply { boundHousehold = householdId }
        val requests = mutableListOf<String>()
        val api = createHouseholdApi {
            requests += it
            when {
                it == "GET /api/households" -> HttpResponsePlan(HttpStatusCode.OK, LIST_ONE_JSON)
                it == "GET /api/households/$householdId" -> HttpResponsePlan(HttpStatusCode.OK, DETAIL_ORIGINAL_JSON)
                it == "PATCH /api/households/$householdId" -> HttpResponsePlan(HttpStatusCode.OK, DETAIL_UPDATED_JSON)
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }
        val manager = createManager(api, setupRepository, store)

        manager.updateFromLocal(local)
        manager.restore(AuthSessionStatus.SIGNED_IN)

        assertEquals(1, requests.count { it == "PATCH /api/households/$householdId" })
        assertNull(store.pendingSettings)
        assertEquals("새 이름", manager.state.value.activeHousehold?.name)
    }

    @Test
    fun updateFromLocalSendsUpdateAndClearsPending() = runTest {
        val setupRepository = FakeSetupRepository(LedgerSetup("원래 가계부", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 0))
        val store = FakeSyncLocalStore().apply { boundHousehold = householdId }
        val updatedSetup = LedgerSetup("새 이름", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 50_000)
        val api = createHouseholdApi {
            when {
                it == "GET /api/households" -> HttpResponsePlan(HttpStatusCode.OK, LIST_ONE_JSON)
                it == "GET /api/households/$householdId" -> HttpResponsePlan(HttpStatusCode.OK, DETAIL_ORIGINAL_JSON)
                it == "PATCH /api/households/$householdId" -> HttpResponsePlan(
                    HttpStatusCode.OK,
                    DETAIL_UPDATED_JSON,
                )
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }

        val manager = createManager(
            api = api,
            syncStore = store,
            setupRepository = setupRepository,
        )
        manager.restore(AuthSessionStatus.SIGNED_IN)
        setupRepository.saveSetup(updatedSetup)
        store.pendingSettings = PendingHouseholdSettings(householdId, 1L)
        manager.state.value.activeHousehold?.let { }

        manager.updateFromLocal(updatedSetup)

        val state = manager.state.value
        assertEquals(HouseholdSessionStatus.READY, state.status)
        assertEquals("새 이름", state.activeHousehold?.name)
        assertEquals(1, store.clearPendingCalls)
        assertEquals(1, store.markPendingCalls)
    }

    @Test
    fun updateFromLocalConflictFallsBackToGet() = runTest {
        var detailGetCalls = 0
        var patchCalls = 0
        val api = createHouseholdApi {
            when {
                it == "GET /api/households" -> HttpResponsePlan(HttpStatusCode.OK, LIST_ONE_JSON)
                it == "GET /api/households/$householdId" -> {
                    detailGetCalls += 1
                    HttpResponsePlan(
                        HttpStatusCode.OK,
                        if (detailGetCalls == 1) DETAIL_ORIGINAL_JSON else
                            DETAIL_ORIGINAL_JSON.replace("\"version\":1", "\"version\":2"),
                    )
                }
                it == "PATCH /api/households/$householdId" -> {
                    patchCalls += 1
                    if (patchCalls == 1) {
                        HttpResponsePlan(HttpStatusCode.Conflict, "{}")
                    } else {
                        HttpResponsePlan(
                            HttpStatusCode.OK,
                            DETAIL_ORIGINAL_JSON
                                .replace("100000", "200000")
                                .replace("\"version\":1", "\"version\":3"),
                        )
                    }
                }
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        }
        val setupRepository = FakeSetupRepository(
            LedgerSetup("원래 가계부", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 100_000),
        )
        val store = FakeSyncLocalStore().apply { boundHousehold = householdId }
        val manager = createManager(api = api, setupRepository = setupRepository, syncStore = store)
        manager.restore(AuthSessionStatus.SIGNED_IN)

        val next = LedgerSetup("원래 가계부", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 200_000)
        manager.updateFromLocal(next)

        val state = manager.state.value
        assertEquals(HouseholdSessionStatus.READY, state.status)
        assertEquals(2, detailGetCalls)
        assertEquals(2, patchCalls)
        assertEquals(3L, state.activeHousehold?.version)
        assertEquals(200_000L, state.activeHousehold?.annualSavingsTargets?.single()?.amountWon)
        assertNull(state.errorMessage)
        assertNull(store.pendingSettings)
        assertEquals(1, store.clearPendingCalls)
    }

    @Test
    fun resetLocalCloudConnectionMovesToNeedsConnection() = runTest {
        val manager = createManager()
        manager.restore(AuthSessionStatus.SIGNED_IN)
        assertEquals(HouseholdSessionStatus.NEEDS_CONNECTION, manager.state.value.status)
    }

    @Test
    fun resetWaitsForInFlightGuardBeforeDeletingLocalCloudBinding() = runTest {
        val guard = SyncOperationGuard()
        val store = FakeSyncLocalStore().apply { boundHousehold = householdId }
        val manager = createManager(syncStore = store, operationGuard = guard)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inFlight = async {
            guard.withExclusive {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val reset = async { manager.resetLocalCloudConnection() }
        assertEquals(0, store.resetCalls)
        release.complete(Unit)
        inFlight.await()
        reset.await()

        assertEquals(1, store.resetCalls)
        assertNull(store.boundHousehold)
        assertEquals(HouseholdSessionStatus.NEEDS_CONNECTION, manager.state.value.status)
    }

    private fun createManager(
        api: MoaLogHouseholdApi = createHouseholdApi {
            when {
                it == "GET /api/households" -> HttpResponsePlan(HttpStatusCode.OK, LIST_ONE_JSON)
                else -> HttpResponsePlan(HttpStatusCode.NotFound, "")
            }
        },
        setupRepository: SetupRepository = FakeSetupRepository(
            LedgerSetup("원래 가계부", listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)), 2026, 100_000),
        ),
        syncStore: SyncLocalStore = FakeSyncLocalStore(),
        operationGuard: SyncOperationGuard = SyncOperationGuard(),
    ) = MoaLogHouseholdSessionManager(api, setupRepository, syncStore, operationGuard)

    private fun createHouseholdApi(
        handler: (String) -> HttpResponsePlan,
    ): MoaLogHouseholdApi {
        val client = createMoaLogHttpClient(
            engine = MockEngine { request ->
                when (val key = "${request.method.value} ${request.url.encodedPath}") {
                    in mapOf("GET /api/households" to 1) -> {
                        val plan = handler(key)
                        respond(plan.body, plan.status, headers = jsonHeaders)
                    }
                    else -> {
                        val plan = handler(key)
                        if (plan.status == HttpStatusCode.OK || plan.status.value != 0) {
                            respond(plan.body, plan.status, headers = jsonHeaders)
                        } else {
                            error("Unhandled API request: $key")
                        }
                    }
                }
            },
            config = MoaLogNetworkConfig("https://api.moalog.test"),
        )
        return MoaLogHouseholdApi(client)
    }

    private class FakeSetupRepository(initial: LedgerSetup) : SetupRepository {
        private val state = MutableStateFlow(initial)
        var saveCalls = 0

        override fun observeSetup() = state
        override suspend fun saveSetup(setup: LedgerSetup) {
            saveCalls += 1
            state.value = setup
        }
    }

    private class FakeSyncLocalStore : SyncLocalStore {
        var boundHousehold: UuidString? = null
        var pendingSettings: PendingHouseholdSettings? = null
        var markPendingCalls = 0
        var clearPendingCalls = 0
        var resetCalls = 0

        override suspend fun persistentDeviceId(uuidFactory: SyncUuidFactory): UuidString = uuid("11111111-1111-4111-8111-111111111111")
        override suspend fun requirePersistentDeviceId(deviceId: UuidString) {}
        override suspend fun boundHouseholdId(): UuidString? = boundHousehold
        override suspend fun bindHousehold(householdId: UuidString) { boundHousehold = householdId }
        override suspend fun pendingHouseholdSettings(): PendingHouseholdSettings? = pendingSettings
        override suspend fun markHouseholdSettingsPending(householdId: UuidString, baseVersion: Long) {
            markPendingCalls += 1
            pendingSettings = PendingHouseholdSettings(householdId, baseVersion)
        }

        override suspend fun clearPendingHouseholdSettings(householdId: UuidString) {
            clearPendingCalls += 1
            if (pendingSettings?.householdId == householdId) pendingSettings = null
        }
        override suspend fun resetCloudBinding() { resetCalls += 1; boundHousehold = null; pendingSettings = null }
        override suspend fun captureLocalChanges(householdId: UuidString, deviceId: UuidString, nowEpochMillis: Long, uuidFactory: SyncUuidFactory) {}
        override suspend fun readyMutations(
            householdId: UuidString,
            nowEpochMillis: Long,
            limit: Int,
        ): List<PersistentSyncMutation> = emptyList()
        override suspend fun nextPendingRetryAt(householdId: UuidString): Long? = null
        override fun observeLocalChanges(): Flow<Unit> = flowOf(Unit)
        override suspend fun acknowledgePush(householdId: UuidString, acknowledgements: List<kr.jm.moalog.core.contracts.AppliedMutationDto>) {}
        override suspend fun markPushFailed(mutationIds: List<UuidString>, nextAttemptAtEpochMillis: Long, error: String) {}
        override suspend fun pullCursor(householdId: UuidString): Long = 0L
        override suspend fun applyPulledChanges(
            householdId: UuidString,
            changes: kotlin.collections.List<kr.jm.moalog.core.contracts.SyncChangeDto>,
            nextCursor: Long,
            resolvePendingConflicts: Boolean,
        ) {}
    }

    private data class HttpResponsePlan(val status: HttpStatusCode = HttpStatusCode.OK, val body: String = "")

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        val householdId = uuid("44444444-4444-4444-8444-444444444444")
        val householdId2 = uuid("55555555-5555-4555-8555-555555555555")

        const val LIST_ONE_JSON = """
            [{
              "householdId":"44444444-4444-4444-8444-444444444444",
              "name":"원래 가계부",
              "currency":"KRW",
              "baseYear":2026,
              "currentUserRole":"owner",
              "updatedAt":"2026-09-14T00:00:00Z"
            }]"""
        const val DETAIL_ORIGINAL_JSON = """
            {
              "householdId":"44444444-4444-4444-8444-444444444444",
              "name":"원래 가계부",
              "currency":"KRW",
              "baseYear":2026,
              "currentUserRole":"owner",
              "members":[
                {"memberId":"11111111-1111-4111-8111-111111111111","displayName":"수아","order":0,"linkedUserId":null},
                {"memberId":"22222222-2222-4222-8222-222222222222","displayName":"종민","order":1,"linkedUserId":null}
              ],
              "annualSavingsTargets":[{"year":2026,"amountWon":100000}],
              "version":1,
              "updatedAt":"2026-09-14T00:00:00Z"
            }"""
        const val DETAIL_UPDATED_JSON = """
            {
              "householdId":"44444444-4444-4444-8444-444444444444",
              "name":"새 이름",
              "currency":"KRW",
              "baseYear":2026,
              "currentUserRole":"owner",
              "members":[
                {"memberId":"11111111-1111-4111-8111-111111111111","displayName":"수아","order":0,"linkedUserId":null},
                {"memberId":"22222222-2222-4222-8222-222222222222","displayName":"종민","order":1,"linkedUserId":null}
              ],
              "annualSavingsTargets":[{"year":2026,"amountWon":50000}],
              "version":2,
              "updatedAt":"2026-09-14T00:00:00Z"
            }"""
    }
}

private fun uuid(value: String): UuidString = UuidString(value)
