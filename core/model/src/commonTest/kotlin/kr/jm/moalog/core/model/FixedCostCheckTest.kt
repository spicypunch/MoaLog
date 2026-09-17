package kr.jm.moalog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedCostCheckTest {
    @Test fun missingAmountAndZeroAreDifferent() {
        val month = YearMonthKey(2026, 5)
        val sheet = FixedCostCheckSheet(
            month,
            listOf(
                FixedCostCheckItem(attributionMonth = month, payerMemberOrder = null, name = "관리비", amountWon = null),
                FixedCostCheckItem(attributionMonth = month, payerMemberOrder = 0, name = "구독", amountWon = 0),
            ),
        )
        assertTrue(sheet.hasMissingAmounts)
        assertEquals(0, sheet.knownTotalWon)
    }

    @Test fun negativeAmountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            FixedCostCheckItem(attributionMonth = YearMonthKey(2026, 5), payerMemberOrder = null, name = "관리비", amountWon = -1)
        }
    }
}
