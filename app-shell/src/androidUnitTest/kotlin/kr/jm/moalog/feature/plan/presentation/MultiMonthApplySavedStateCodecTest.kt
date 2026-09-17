package kr.jm.moalog.feature.plan.presentation

import androidx.lifecycle.SavedStateHandle
import kr.jm.moalog.core.database.ExistingPlanPolicy
import kr.jm.moalog.core.database.PlanCopyResult
import kr.jm.moalog.core.model.YearMonthKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MultiMonthApplySavedStateCodecTest {
    @Test
    fun roundTripPreservesSelectionPolicyAndPreviewStep() {
        val handle = SavedStateHandle()
        val snapshot = MultiMonthApplyDraftSnapshot(
            displayedYear = 2027,
            selectedMonths = listOf(YearMonthKey(2026, 12), YearMonthKey(2027, 1)),
            policy = ExistingPlanPolicy.Overwrite,
            previewRequested = true,
        )

        MultiMonthApplySavedStateCodec.write(handle, snapshot)

        assertEquals(snapshot, MultiMonthApplySavedStateCodec.read(handle))
    }

    @Test
    fun malformedMonthsAreIgnoredAndClearRemovesSnapshot() {
        val handle = SavedStateHandle(
            mapOf(
                "multi_month_apply.draft.present" to true,
                "multi_month_apply.draft.year" to 2026,
                "multi_month_apply.draft.months" to arrayListOf("2026:6", "broken", "2026:13"),
                "multi_month_apply.draft.policy" to ExistingPlanPolicy.KeepExisting.name,
                "multi_month_apply.draft.preview" to false,
            ),
        )

        val restored = MultiMonthApplySavedStateCodec.read(handle)
        assertEquals(listOf(YearMonthKey(2026, 6)), restored?.selectedMonths)

        MultiMonthApplySavedStateCodec.clear(handle)
        assertNull(MultiMonthApplySavedStateCodec.read(handle))
    }

    @Test
    fun completionMessageSeparatesInsertedOverwrittenAndKeptCounts() {
        val result = PlanCopyResult(
            targetMonthCount = 3,
            insertedItemCount = 2,
            overwrittenItemCount = 4,
            skippedConflictCount = 1,
        )

        assertEquals(6, result.changedItemCount)
        assertEquals(
            "3개월 · 신규 2개 추가 · 기존 4개 덮어쓰기 · 기존 1개 유지",
            planCopyCompletionMessage(result),
        )
    }
}
