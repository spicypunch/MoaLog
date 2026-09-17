package kr.jm.moalog.appshell

import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppShellStateTest {
    private val setup = LedgerSetup(
        ledgerName = "모아로그",
        members = listOf(
            LedgerMember("수아", 0),
            LedgerMember("종민", 1),
        ),
        baseYear = 2026,
        annualSavingsTargetWon = null,
    )

    @Test
    fun setupFlowSwitchesStateWhenSetupBecomesAvailableOrMissing() = runTest {
        val repository = InMemorySetupRepository(flowOfValues = null)
        val scope = CoroutineScope(this.coroutineContext + SupervisorJob())
        val stateHolder = AppShellStateHolder(repository, scope)
        try {
            advanceUntilIdle()
            assertEquals(AppShellUiState.NeedsSetup, stateHolder.state.value)

            repository.pushState(setup)
            advanceUntilIdle()
            assertEquals(AppShellUiState.Ready(setup), stateHolder.state.value)

            repository.pushState(null)
            advanceUntilIdle()
            assertEquals(AppShellUiState.NeedsSetup, stateHolder.state.value)
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun retryRecoversAfterLoadFailure() = runTest {
        val repository = FlakySetupRepository()
        val scope = CoroutineScope(this.coroutineContext + SupervisorJob())
        val stateHolder = AppShellStateHolder(repository, scope)
        try {
            advanceUntilIdle()
            assertEquals(AppShellUiState.LoadFailed, stateHolder.state.value)

            repository.failOnce = false
            stateHolder.retry()
            advanceUntilIdle()
            assertIs<AppShellUiState.NeedsSetup>(stateHolder.state.value)
        } finally {
            scope.cancel()
        }
    }
}

private class InMemorySetupRepository(
    initial: LedgerSetup? = null,
    private val flowOfValues: LedgerSetup? = null,
) : SetupRepository {
    private val stateFlow = MutableStateFlow(flowOfValues ?: initial)

    fun pushState(value: LedgerSetup?) {
        stateFlow.value = value
    }

    override fun observeSetup(): Flow<LedgerSetup?> = stateFlow
    override suspend fun saveSetup(setup: LedgerSetup) {
        stateFlow.value = setup
    }
}

private class FlakySetupRepository : SetupRepository {
    var failOnce: Boolean = true

    override fun observeSetup(): Flow<LedgerSetup?> = flow {
        if (failOnce) {
            failOnce = false
            throw RuntimeException("setup load failed")
        }
        emit(null)
    }

    override suspend fun saveSetup(setup: LedgerSetup) = Unit
}
