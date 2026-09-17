package kr.jm.moalog.feature.setup.presentation

import kr.jm.moalog.core.model.LedgerSetup
import kr.jm.moalog.feature.setup.domain.SetupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupValidationTest {
    @Test
    fun optionalMoneyDistinguishesMissingZeroAndOverflow() {
        assertEquals(WonParseResult.Valid(null), parseOptionalWon(""))
        assertEquals(WonParseResult.Valid(0), parseOptionalWon("0"))
        assertEquals(WonParseResult.Valid(50_000_000), parseOptionalWon("50,000,000"))
        assertIs<WonParseResult.Invalid>(parseOptionalWon("-1"))
        assertIs<WonParseResult.Invalid>(parseOptionalWon("999999999999999999999"))
    }

    @Test
    fun validationRequiresAllIdentityFieldsAndFourDigitYear() {
        val (errors, setup) = validateSetup(SetupUiState(baseYear = "26"))
        assertTrue(errors.hasAny)
        assertTrue(errors.ledgerName != null)
        assertTrue(errors.firstMemberName != null)
        assertTrue(errors.secondMemberName != null)
        assertTrue(errors.baseYear != null)
        assertNull(setup)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedSaveKeepsInputAndAllowsRetry() = runTest {
        val repository = FakeRepository(fail = true)
        val holder = SetupStateHolder(repository, this)
        fillValidForm(holder)
        holder.onAction(SetupAction.Save)
        advanceUntilIdle()

        assertEquals("우리집", holder.state.value.ledgerName)
        assertFalse(holder.state.value.isSaving)
        assertTrue(holder.state.value.persistenceError != null)

        repository.fail = false
        holder.onAction(SetupAction.Save)
        advanceUntilIdle()
        assertEquals(50_000_000, repository.saved?.annualSavingsTargetWon)
        assertNull(holder.state.value.persistenceError)
    }

    private fun fillValidForm(holder: SetupStateHolder) {
        holder.onAction(SetupAction.LedgerNameChanged("우리집"))
        holder.onAction(SetupAction.FirstMemberNameChanged("수아"))
        holder.onAction(SetupAction.SecondMemberNameChanged("종민"))
        holder.onAction(SetupAction.BaseYearChanged("2026"))
        holder.onAction(SetupAction.AnnualSavingsTargetChanged("50,000,000"))
    }

    private class FakeRepository(var fail: Boolean) : SetupRepository {
        var saved: LedgerSetup? = null
        override fun observeSetup(): Flow<LedgerSetup?> = flowOf(saved)
        override suspend fun saveSetup(setup: LedgerSetup) {
            if (fail) error("disk full")
            saved = setup
        }
    }
}
