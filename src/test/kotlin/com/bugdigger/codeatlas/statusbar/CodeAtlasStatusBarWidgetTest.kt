package com.bugdigger.codeatlas.statusbar

import com.bugdigger.codeatlas.index.IndexState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-text rendering of [CodeAtlasStatusBarWidget.textFor] for each
 * [IndexState] variant. Covers what the deleted `IndexStatusBarTest` used to
 * cover plus the new "CodeAtlas: " prefix.
 */
class CodeAtlasStatusBarWidgetTest {

    @Test
    fun emptyState() {
        assertEquals(
            "CodeAtlas: no index",
            CodeAtlasStatusBarWidget.textFor(IndexState.Empty),
        )
    }

    @Test
    fun buildingFullIndexShowsProgress() {
        assertEquals(
            "CodeAtlas: indexing 412/9800",
            CodeAtlasStatusBarWidget.textFor(IndexState.BuildingFullIndex(412, 9800)),
        )
    }

    @Test
    fun updatingShowsCount() {
        assertEquals(
            "CodeAtlas: updating – 1234 symbols",
            CodeAtlasStatusBarWidget.textFor(IndexState.Updating(1234)),
        )
    }

    @Test
    fun readyShowsCount() {
        assertEquals(
            "CodeAtlas: ready · 5678 symbols",
            CodeAtlasStatusBarWidget.textFor(IndexState.Ready(5678)),
        )
    }
}
