package kr.jm.moalog.appshell

import androidx.lifecycle.SavedStateHandle
import kr.jm.moalog.feature.setup.presentation.LedgerSettingsDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LedgerSettingsDraftCodecTest {
    @Test
    fun roundTripsAndClearsAllEditableNames() {
        val handle = SavedStateHandle()
        val draft = LedgerSettingsDraft("우리 집", "수아", "종민")

        LedgerSettingsDraftCodec.write(handle, draft)
        assertEquals(draft, LedgerSettingsDraftCodec.read(handle))
        LedgerSettingsDraftCodec.clear(handle)
        assertNull(LedgerSettingsDraftCodec.read(handle))
    }
}
