package kr.jm.moalog.feature.assets.presentation

import androidx.lifecycle.SavedStateHandle
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.YearMonthKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AssetEditorSavedStateCodecTest {
    @Test
    fun roundTripsCompleteDraftAtSupportedMinimumMonth() {
        val handle = SavedStateHandle()
        val draft = AssetEditorDraftSnapshot(
            AssetEditorMode.NewAsset,
            "여행 통장",
            AssetType.Investment,
            1,
            "장기 여행",
            AssetKind.PurposeAccount,
            YearMonthKey(1900, 1),
            "250000",
            "12",
            "200000",
            "10000",
        )

        AssetEditorSavedStateCodec.write(handle, draft)
        assertEquals(draft, AssetEditorSavedStateCodec.read(handle))
        AssetEditorSavedStateCodec.clear(handle)
        assertNull(AssetEditorSavedStateCodec.read(handle))
    }

    @Test
    fun assetScreenStateRestoresMonthAndSortAtSupportedMaximum() {
        val viewModel = AssetScreenSavedStateViewModel(SavedStateHandle())
        val month = YearMonthKey(9999, 12)

        viewModel.save(month, AssetSort.Name)

        assertEquals(month, viewModel.restoredMonth())
        assertEquals(AssetSort.Name, viewModel.restoredSort())
    }
}
