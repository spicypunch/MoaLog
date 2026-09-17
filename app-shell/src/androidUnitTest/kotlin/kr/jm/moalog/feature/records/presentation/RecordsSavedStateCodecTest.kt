package kr.jm.moalog.feature.records.presentation

import androidx.lifecycle.SavedStateHandle
import kr.jm.moalog.core.model.YearMonthKey
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordsSavedStateCodecTest {
    @Test
    fun recordsMonthFiltersAndSortRoundTrip() {
        val handle = SavedStateHandle()
        val expected = RecordsPreferencesSnapshot(
            month = YearMonthKey(1900, 1),
            filters = ExpenseFilters(setOf("date", "travel"), overspentOnly = true, sort = ExpenseSort.Oldest),
        )

        RecordsSavedStateCodec.write(handle, expected)

        assertEquals(expected, RecordsSavedStateCodec.read(handle))
    }

    @Test
    fun expenseEditorDraftRoundTrips() {
        val handle = SavedStateHandle()
        val expected = ExpenseEditorDraftSnapshot(
            amount = "120000",
            categoryId = "date",
            attributionMonth = "1900-01",
            actualDate = "1900-01-31",
            detail = "저녁",
            overspent = true,
        )

        ExpenseEditorSavedStateCodec.write(handle, expected)

        assertEquals(expected, ExpenseEditorSavedStateCodec.read(handle))
    }
}
