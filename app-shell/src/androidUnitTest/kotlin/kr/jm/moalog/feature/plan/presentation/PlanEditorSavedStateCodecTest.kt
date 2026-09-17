package kr.jm.moalog.feature.plan.presentation

import androidx.lifecycle.SavedStateHandle
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanEditorSavedStateCodecTest {
    @Test
    fun roundTrip_preservesDraftAndTransientFormState() {
        val handle = SavedStateHandle()
        val snapshot = PlanEditorDraftSnapshot(
            type = PlanItemType.Savings,
            month = "2026-09",
            name = "여행 적금",
            amount = "-120000",
            category = "예적금",
            status = PlanItemStatus.Confirmed,
            ownerMemberOrder = null,
            memo = "10월 여행 전에 정리",
            includePurposeAccount = true,
            includeNetSavings = false,
            errors = PlanEditorErrors(
                month = "귀속월을 선택해 주세요",
                name = "항목명을 입력해 주세요",
                amount = "금액을 숫자로 입력해 주세요",
                category = "카테고리를 선택해 주세요",
            ),
            showDiscardConfirmation = true,
            hasUnsavedChanges = true,
            selectedCatalogId = 41,
        )

        PlanEditorSavedStateCodec.write(handle, snapshot)

        assertEquals(snapshot, PlanEditorSavedStateCodec.read(handle))

        PlanEditorSavedStateCodec.clear(handle)
        assertNull(PlanEditorSavedStateCodec.read(handle))
    }

    @Test
    fun roundTrip_preservesOwnerSelection() {
        val handle = SavedStateHandle()
        val snapshot = PlanEditorDraftSnapshot(
            type = PlanItemType.FixedExpense,
            month = "2026-10",
            name = "월세",
            amount = "850000",
            category = "주거비",
            status = PlanItemStatus.Estimated,
            ownerMemberOrder = 2,
            memo = "",
            includePurposeAccount = false,
            includeNetSavings = false,
            errors = PlanEditorErrors(),
            showDiscardConfirmation = false,
            hasUnsavedChanges = true,
        )

        PlanEditorSavedStateCodec.write(handle, snapshot)

        assertEquals(snapshot, PlanEditorSavedStateCodec.read(handle))
    }
}
