package kr.jm.moalog.feature.plan.presentation

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kr.jm.moalog.core.model.PlanItemType

class PlanEditorValidationTest {
    @Test fun negativeAmountIsAcceptedOnlyForSavings() {
        val fixed = PlanEditorUiState(type = PlanItemType.FixedExpense, month = "2026-05", name = "관리비", amount = "-1", category = "주거/통신", isLoading = false)
        assertNull(validatePlanEditor(fixed).second)
        assertNotNull(validatePlanEditor(fixed.copy(type = PlanItemType.Savings)).second)
    }

    @Test fun zeroIsAValidEnteredAmount() {
        val state = PlanEditorUiState(type = PlanItemType.Income, month = "2026-05", name = "급여", amount = "0", category = "급여", isLoading = false)
        assertNotNull(validatePlanEditor(state).second)
    }
}
