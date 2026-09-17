package kr.jm.moalog.feature.assets.presentation

import kr.jm.moalog.core.model.*
import kr.jm.moalog.feature.assets.domain.AssetRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class AssetDetailEditorStateTest {
    private val may = YearMonthKey(2026, 5)
    private val asset = AssetItem(7, "수아 비상금", AssetType.Deposit, memo = "비상 자금")

    @Test fun detailUsesConfirmedValueBeforeRuleAndShowsMissingMonthsHonestly() {
        val portfolio = AssetPortfolio(
            listOf(asset),
            listOf(AssetValuation(7, may, 1_500L)),
            listOf(AssetGrowthRule(7, may, 3, 1_000L, 100L)),
        )
        val state = buildAssetDetailState(AssetDetailArgs(7, may), portfolio)
        assertEquals(1_500L, state.value.amountWon)
        assertEquals(AssetValueStatus.Confirmed, state.value.status)
        assertEquals(1_600L, state.chart.last { it.month == may.plusMonths(1) }.value.amountWon)
        assertEquals(may.plusMonths(2), state.records.first().month)
        assertEquals(may.plusMonths(-2), state.records.last().month)
    }

    @Test fun missingAssetReturnsRecoverableErrorState() {
        val state = buildAssetDetailState(AssetDetailArgs(99, may), AssetPortfolio(emptyList(), emptyList()))
        assertFalse(state.isLoading)
        assertNotNull(state.loadError)
    }

    @Test fun detailShowsNegativeSelectedMonthDeltaAndIsSafeAtMaximumMonth() {
        val max = YearMonthKey(9999, 12)
        val portfolio = AssetPortfolio(
            listOf(asset),
            listOf(AssetValuation(7, max.plusMonths(-1), 2_000L), AssetValuation(7, max, 1_500L)),
        )
        val state = buildAssetDetailState(AssetDetailArgs(7, max), portfolio)
        assertEquals(-500L, state.previousMonthDeltaWon)
        assertEquals(max, state.chart.last().month)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun detailDeletesConfirmedValuationGrowthRuleAndAssetThroughExplicitOperations() = runTest {
        val portfolio = AssetPortfolio(
            listOf(asset),
            listOf(AssetValuation(7, may, 1_500L)),
            listOf(AssetGrowthRule(7, may, 3, 1_000L, 100L)),
        )
        val valuationRepository = RecordingRepository(portfolio)
        val valuationHolder = AssetDetailStateHolder(AssetDetailArgs(7, may), valuationRepository, backgroundScope)
        runCurrent()
        valuationHolder.onAction(AssetDetailAction.DeleteSelectedValuation)
        runCurrent()
        assertEquals(7L to may, valuationRepository.deletedValuation)
        valuationHolder.close()

        val ruleRepository = RecordingRepository(portfolio)
        val ruleHolder = AssetDetailStateHolder(AssetDetailArgs(7, may), ruleRepository, backgroundScope)
        runCurrent()
        ruleHolder.onAction(AssetDetailAction.DeleteGrowthRule)
        runCurrent()
        assertEquals(7L, ruleRepository.deletedRule)
        ruleHolder.close()

        val assetRepository = RecordingRepository(portfolio)
        val assetHolder = AssetDetailStateHolder(AssetDetailArgs(7, may), assetRepository, backgroundScope)
        runCurrent()
        assetHolder.onAction(AssetDetailAction.DeleteAsset)
        runCurrent()
        assertEquals(7L, assetRepository.deletedAsset)
        assertTrue(assetHolder.state.value.deleted)
        assetHolder.close()
    }

    @Test fun newAssetValidationRequiresNameAndAmount() {
        val state = AssetEditorUiState(AssetEditorArgs(null, may, AssetEditorMode.NewAsset))
        assertEquals("자산 이름을 입력해 주세요", validate(state))
        assertEquals("초기 금액을 입력해 주세요", validate(state.copy(name = "적금")))
        assertNull(validate(state.copy(name = "적금", amount = "0")))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun newAssetSaveUsesAtomicInitialValuationOperation() = runTest {
        val repository = RecordingRepository(AssetPortfolio(emptyList(), emptyList()))
        val holder = AssetEditorStateHolder(AssetEditorArgs(null, may, AssetEditorMode.NewAsset), repository, backgroundScope)
        holder.onAction(AssetEditorAction.ChangeName("국민은행 적금"))
        holder.onAction(AssetEditorAction.ChangeAmount("1,230,000"))
        holder.onAction(AssetEditorAction.Save)
        runCurrent()
        assertEquals("국민은행 적금", repository.added?.first?.name)
        assertEquals(may, repository.added?.second)
        assertEquals(1_230_000L, repository.added?.third)
        assertEquals(88L, holder.state.value.savedAssetId)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun editorRestoresFullDraftAndDoubleSaveIsGated() = runTest {
        val repository = RecordingRepository(AssetPortfolio(emptyList(), emptyList()))
        val holder = AssetEditorStateHolder(AssetEditorArgs(null, may, AssetEditorMode.NewAsset), repository, backgroundScope)
        val draft = AssetEditorDraftSnapshot(
            AssetEditorMode.NewAsset, "여행 자금", AssetType.Investment, 1, "메모", AssetKind.PurposeAccount,
            may.plusMonths(1), "2500", "8", "1000", "100",
        )
        holder.onAction(AssetEditorAction.RestoreDraft(draft))
        assertEquals(draft, holder.state.value.toDraftSnapshot())
        assertTrue(holder.state.value.isDirty)
        holder.onAction(AssetEditorAction.Save)
        holder.onAction(AssetEditorAction.Save)
        runCurrent()
        assertEquals(1, repository.addCount)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun editorLoadFailureCanRetryWithoutLosingItsRouteArguments() = runTest {
        val repository = ToggleFailureRepository(AssetPortfolio(listOf(asset), listOf(AssetValuation(7, may, 900L))))
        val holder = AssetEditorStateHolder(AssetEditorArgs(7, may, AssetEditorMode.MonthlyValue), repository, backgroundScope)
        runCurrent()
        assertEquals(AssetEditorOperation.Load, holder.state.value.failedOperation)

        repository.fail = false
        holder.onAction(AssetEditorAction.Retry)
        runCurrent()

        assertFalse(holder.state.value.isLoading)
        assertNull(holder.state.value.error)
        assertEquals("900", holder.state.value.amount)
        assertEquals(may, holder.state.value.month)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun newPurposeAccountPersistsPurposeKind() = runTest {
        val repository = RecordingRepository(AssetPortfolio(emptyList(), emptyList()))
        val holder = AssetEditorStateHolder(
            AssetEditorArgs(null, may, AssetEditorMode.NewAsset, purposeAccount = true),
            repository,
            backgroundScope,
        )
        holder.onAction(AssetEditorAction.ChangeName("여행통장"))
        holder.onAction(AssetEditorAction.ChangeAmount("0"))
        holder.onAction(AssetEditorAction.Save)
        runCurrent()

        assertEquals(AssetKind.PurposeAccount, repository.added?.first?.kind)
        assertEquals(0L, repository.added?.third)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun existingAssetMetadataAndValuationUseOneAtomicRepositoryOperation() = runTest {
        val repository = RecordingRepository(AssetPortfolio(listOf(asset), listOf(AssetValuation(7, may, 1_000L))))
        val holder = AssetEditorStateHolder(AssetEditorArgs(7, may, AssetEditorMode.NewAsset), repository, backgroundScope)
        runCurrent()
        holder.onAction(AssetEditorAction.ChangeName("수정 자산"))
        holder.onAction(AssetEditorAction.ChangeType(AssetType.Investment))
        holder.onAction(AssetEditorAction.ChangeMemo("장기 투자"))
        holder.onAction(AssetEditorAction.ChangeKind(AssetKind.PurposeAccount))
        holder.onAction(AssetEditorAction.ChangeAmount("1300"))
        holder.onAction(AssetEditorAction.Save)
        runCurrent()

        assertEquals("수정 자산", repository.atomicUpdate?.first?.name)
        assertEquals(AssetType.Investment, repository.atomicUpdate?.first?.type)
        assertEquals("장기 투자", repository.atomicUpdate?.first?.memo)
        assertEquals(AssetKind.PurposeAccount, repository.atomicUpdate?.first?.kind)
        assertEquals(1_300L, repository.atomicUpdate?.second?.amountWon)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun monthlyValueAndGrowthRuleAreSavedSeparately() = runTest {
        val repository = RecordingRepository(AssetPortfolio(listOf(asset), listOf(AssetValuation(7, may, 1_000L))))
        val monthly = AssetEditorStateHolder(AssetEditorArgs(7, may, AssetEditorMode.MonthlyValue), repository, backgroundScope)
        runCurrent()
        monthly.onAction(AssetEditorAction.ChangeAmount("2000")); monthly.onAction(AssetEditorAction.Save); runCurrent()
        assertEquals(AssetValuation(7, may, 2_000L), repository.valuation)
        monthly.close()

        val rule = AssetEditorStateHolder(AssetEditorArgs(7, may, AssetEditorMode.GrowthRule), repository, backgroundScope)
        runCurrent()
        rule.onAction(AssetEditorAction.ChangeDuration("6")); rule.onAction(AssetEditorAction.ChangeBaseAmount("2000")); rule.onAction(AssetEditorAction.ChangeMonthlyIncrease("300")); rule.onAction(AssetEditorAction.Save); runCurrent()
        assertEquals(AssetGrowthRule(7, may, 6, 2_000L, 300L), repository.rule)
        rule.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun monthlyModeUpdatesAmountWhenMonthChangesByConfirmedEstimatedOrMissing() = runTest {
        val repository = RecordingRepository(
            AssetPortfolio(
                assets = listOf(asset),
                valuations = listOf(
                    AssetValuation(7, may, 1_500_000L),
                    AssetValuation(7, may.plusMonths(1), 1_700_000L),
                ),
                growthRules = listOf(AssetGrowthRule(7, may, 3, 1_000_000L, 100_000L)),
            ),
        )
        val holder = AssetEditorStateHolder(
            AssetEditorArgs(7, may, AssetEditorMode.MonthlyValue),
            repository,
            backgroundScope,
        )
        runCurrent()

        holder.onAction(AssetEditorAction.ChangeMonth(may.plusMonths(1)))
        runCurrent()
        assertEquals("1700000", holder.state.value.amount)

        holder.onAction(AssetEditorAction.ChangeMonth(may.plusMonths(2)))
        runCurrent()
        assertEquals("1800000", holder.state.value.amount)

        holder.onAction(AssetEditorAction.ChangeMonth(may.plusMonths(4)))
        runCurrent()
        assertEquals("", holder.state.value.amount)

        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun metadataModeRefreshesValuationWhenMonthChangesAndDoesNotSaveAStaleAmount() = runTest {
        val repository = RecordingRepository(
            AssetPortfolio(
                listOf(asset),
                listOf(AssetValuation(7, may, 1_000L), AssetValuation(7, may.plusMonths(1), 2_000L)),
            ),
        )
        val holder = AssetEditorStateHolder(AssetEditorArgs(7, may, AssetEditorMode.NewAsset), repository, backgroundScope)
        runCurrent()

        holder.onAction(AssetEditorAction.ChangeMonth(may.plusMonths(1)))
        assertEquals("2000", holder.state.value.amount)
        holder.onAction(AssetEditorAction.Save)
        runCurrent()

        assertEquals(may.plusMonths(1), repository.atomicUpdate?.second?.valuationMonth)
        assertEquals(2_000L, repository.atomicUpdate?.second?.amountWon)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun growthRuleEditorAcceptsAndSavesSignedMonthlyChange() = runTest {
        val repository = RecordingRepository(AssetPortfolio(listOf(asset), listOf(AssetValuation(7, may, 1_000L))))
        val holder = AssetEditorStateHolder(AssetEditorArgs(7, may, AssetEditorMode.GrowthRule), repository, backgroundScope)
        runCurrent()

        holder.onAction(AssetEditorAction.ChangeDuration("6"))
        holder.onAction(AssetEditorAction.ChangeBaseAmount("1000"))
        holder.onAction(AssetEditorAction.ChangeMonthlyIncrease("-100"))
        assertEquals("-100", holder.state.value.monthlyIncrease)
        holder.onAction(AssetEditorAction.Save)
        runCurrent()

        assertEquals(-100L, repository.rule?.monthlyIncreaseWon)
        holder.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun growthRuleModeRestoresStoredRuleFields() = runTest {
        val rule = AssetGrowthRule(7, YearMonthKey(2026, 7), 7, 2_000_000L, 120_000L)
        val repository = RecordingRepository(
            AssetPortfolio(
                assets = listOf(asset),
                valuations = listOf(AssetValuation(7, may, 1_500_000L)),
                growthRules = listOf(rule),
            ),
        )
        val holder = AssetEditorStateHolder(
            AssetEditorArgs(7, may, AssetEditorMode.MonthlyValue),
            repository,
            backgroundScope,
        )
        runCurrent()

        holder.onAction(AssetEditorAction.SelectMode(AssetEditorMode.GrowthRule))
        runCurrent()

        assertEquals(AssetEditorMode.GrowthRule, holder.state.value.selectedMode)
        assertEquals(rule.startMonth, holder.state.value.month)
        assertEquals(rule.durationMonths.toString(), holder.state.value.duration)
        assertEquals(rule.baseAmountWon.toString(), holder.state.value.baseAmount)
        assertEquals(rule.monthlyIncreaseWon.toString(), holder.state.value.monthlyIncrease)

        holder.close()
    }
}

private class ToggleFailureRepository(private val portfolio: AssetPortfolio) : AssetRepository {
    var fail = true
    override fun observePortfolio(): Flow<AssetPortfolio> = if (fail) flow { error("expected") } else flowOf(portfolio)
    override suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long): Long = 1
    override suspend fun updateAsset(asset: AssetItem) = Unit
    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) = Unit
    override suspend fun saveValuation(valuation: AssetValuation) = Unit
    override suspend fun saveGrowthRule(rule: AssetGrowthRule) = Unit
}

private class RecordingRepository(portfolio: AssetPortfolio) : AssetRepository {
    private val flow = MutableStateFlow(portfolio)
    var added: Triple<AssetItem, YearMonthKey, Long>? = null
    var addCount: Int = 0
    var valuation: AssetValuation? = null
    var rule: AssetGrowthRule? = null
    var atomicUpdate: Pair<AssetItem, AssetValuation>? = null
    var deletedAsset: Long? = null
    var deletedValuation: Pair<Long, YearMonthKey>? = null
    var deletedRule: Long? = null
    override fun observePortfolio(): Flow<AssetPortfolio> = flow
    override suspend fun addAsset(asset: AssetItem, month: YearMonthKey, amountWon: Long): Long { addCount++; added = Triple(asset, month, amountWon); return 88L }
    override suspend fun updateAsset(asset: AssetItem) = Unit
    override suspend fun updateAssetWithValuation(asset: AssetItem, valuation: AssetValuation) { atomicUpdate = asset to valuation }
    override suspend fun saveValuation(valuation: AssetValuation) { this.valuation = valuation }
    override suspend fun saveGrowthRule(rule: AssetGrowthRule) { this.rule = rule }
    override suspend fun deleteAsset(assetId: Long) { deletedAsset = assetId }
    override suspend fun deleteValuation(assetId: Long, month: YearMonthKey) { deletedValuation = assetId to month }
    override suspend fun deleteGrowthRule(assetId: Long) { deletedRule = assetId }
}
