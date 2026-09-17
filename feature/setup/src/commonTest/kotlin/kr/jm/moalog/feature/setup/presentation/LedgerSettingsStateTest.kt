package kr.jm.moalog.feature.setup.presentation

import kr.jm.moalog.core.model.LedgerMember
import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerSettingsStateTest {
    private val setup = LedgerSetup(
        ledgerName = "우리 가계부",
        members = listOf(LedgerMember("수아", 0), LedgerMember("종민", 1)),
        baseYear = 2026,
        annualSavingsTargetWon = 1_000_000,
        annualSavingsTargetsWon = mapOf(2025 to 900_000, 2026 to 1_000_000, 2027 to 1_100_000),
    )

    @Test
    fun seedsCurrentValuesAndPreservesEveryAnnualTargetWhenSavingNames() = runTest {
        val repository = FakeSettingsRepository(setup)
        val holder = LedgerSettingsStateHolder(repository, backgroundScope)
        runCurrent()
        assertEquals("우리 가계부", holder.state.value.ledgerName)

        holder.onAction(LedgerSettingsAction.ChangeLedgerName("둘의 가계부"))
        holder.onAction(LedgerSettingsAction.ChangeFirstMemberName("새수아"))
        holder.onAction(LedgerSettingsAction.Save)
        runCurrent()

        val saved = repository.saved.single()
        assertEquals("둘의 가계부", saved.ledgerName)
        assertEquals(listOf("새수아", "종민"), saved.members.sortedBy { it.order }.map { it.displayName })
        assertEquals(setup.annualSavingsTargetsWon, saved.annualSavingsTargetsWon)
        assertEquals(setup.annualSavingsTargetWon, saved.annualSavingsTargetWon)
        assertEquals(setup.baseYear, saved.baseYear)
        assertTrue(holder.state.value.saved)
        holder.close()
    }

    @Test
    fun restoredDraftTracksDirtyAndRevertingClearsDirty() = runTest {
        val holder = LedgerSettingsStateHolder(FakeSettingsRepository(setup), backgroundScope)
        holder.onAction(LedgerSettingsAction.RestoreDraft(LedgerSettingsDraft("복원", "A", "B")))
        runCurrent()
        assertTrue(holder.state.value.isDirty)

        holder.onAction(LedgerSettingsAction.ChangeLedgerName(setup.ledgerName))
        holder.onAction(LedgerSettingsAction.ChangeFirstMemberName("수아"))
        holder.onAction(LedgerSettingsAction.ChangeSecondMemberName("종민"))
        assertFalse(holder.state.value.isDirty)
        holder.close()
    }

    @Test
    fun loadFailureCanRetry() = runTest {
        val repository = FakeSettingsRepository(setup, failLoads = true)
        val holder = LedgerSettingsStateHolder(repository, backgroundScope)
        runCurrent()
        assertTrue(holder.state.value.error != null)

        repository.failLoads = false
        holder.onAction(LedgerSettingsAction.Retry)
        runCurrent()
        assertEquals("우리 가계부", holder.state.value.ledgerName)
        holder.close()
    }

    @Test
    fun failedSaveCanRetryWithoutChangingTheDraft() = runTest {
        val repository = FakeSettingsRepository(setup, failSaves = true)
        val holder = LedgerSettingsStateHolder(repository, backgroundScope)
        runCurrent()
        holder.onAction(LedgerSettingsAction.ChangeLedgerName("둘의 가계부"))
        holder.onAction(LedgerSettingsAction.Save)
        runCurrent()
        assertTrue(holder.state.value.canSave)

        repository.failSaves = false
        holder.onAction(LedgerSettingsAction.Save)
        runCurrent()
        assertEquals("둘의 가계부", repository.saved.single().ledgerName)
        assertTrue(holder.state.value.saved)
        holder.close()
    }
}

private class FakeSettingsRepository(
    setup: LedgerSetup,
    var failLoads: Boolean = false,
    var failSaves: Boolean = false,
) : SetupRepository {
    private val data = MutableStateFlow<LedgerSetup?>(setup)
    val saved = mutableListOf<LedgerSetup>()
    override fun observeSetup(): Flow<LedgerSetup?> = if (failLoads) flow { error("expected") } else data
    override suspend fun saveSetup(setup: LedgerSetup) {
        if (failSaves) error("expected")
        saved += setup
        data.value = setup
    }
}
