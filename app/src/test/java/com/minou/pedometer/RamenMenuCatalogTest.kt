package com.minou.pedometer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RamenMenuCatalogTest {

    @Test
    fun toggleSelection_chashuItemsAreExclusive() {
        val withOneSlice = RamenMenuCatalog.toggleSelection(
            selectedNames = RamenMenuCatalog.requiredItemNames,
            targetName = "チャーシュー1枚",
        )
        assertTrue(withOneSlice.contains("チャーシュー1枚"))
        assertTrue(withOneSlice.contains("ラーメン"))

        val withThreeSlices = RamenMenuCatalog.toggleSelection(
            selectedNames = withOneSlice,
            targetName = "チャーシュー3枚",
        )
        assertFalse(withThreeSlices.contains("チャーシュー1枚"))
        assertTrue(withThreeSlices.contains("チャーシュー3枚"))
        assertTrue(withThreeSlices.contains("ラーメン"))
    }

    @Test
    fun toggleSelection_tappingSameChashuTwiceTurnsOffOnlyChashu() {
        val selected = RamenMenuCatalog.toggleSelection(
            selectedNames = RamenMenuCatalog.requiredItemNames,
            targetName = "チャーシュー2枚",
        )
        val cleared = RamenMenuCatalog.toggleSelection(
            selectedNames = selected,
            targetName = "チャーシュー2枚",
        )

        assertTrue(cleared.contains("ラーメン"))
        assertTrue(cleared.intersect(RamenMenuCatalog.chashuItemNames).isEmpty())
    }
}
