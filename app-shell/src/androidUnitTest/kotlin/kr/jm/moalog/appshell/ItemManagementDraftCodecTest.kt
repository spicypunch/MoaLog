package kr.jm.moalog.appshell

import androidx.lifecycle.SavedStateHandle
import kr.jm.moalog.feature.plan.presentation.ItemManagementDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemManagementDraftCodecTest {
    @Test
    fun roundTripsEveryEditableProperty() {
        val handle = SavedStateHandle()
        val draft = ItemManagementDraft(
            stableId = "plan:42",
            name = "신혼집 청약",
            classification = "청약",
            ownerMemberOrder = 1,
            includePurposeAccount = true,
            includeNetSavings = false,
        )

        ItemManagementDraftCodec.write(handle, draft)

        assertEquals(draft, ItemManagementDraftCodec.read(handle))
    }

    @Test
    fun clearRemovesRestorableDraft() {
        val handle = SavedStateHandle()
        ItemManagementDraftCodec.write(handle, ItemManagementDraft(name = "식비"))

        ItemManagementDraftCodec.clear(handle)

        assertNull(ItemManagementDraftCodec.read(handle))
    }
}
