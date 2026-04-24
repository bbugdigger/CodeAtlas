package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.IndexState
import com.intellij.ui.components.JBLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexStatusBarTest {

    @Test
    fun rendersExpectedTextForEachIndexState() {
        val bar = IndexStatusBar()
        val label = labelOf(bar)

        assertEquals("No index yet", label.text)

        bar.update(IndexState.BuildingFullIndex(done = 3, total = 10))
        assertEquals("Indexing 3/10", label.text)

        bar.update(IndexState.Updating(count = 42))
        assertEquals("Updating – 42 symbols", label.text)

        bar.update(IndexState.Ready(count = 99))
        assertEquals("Ready · 99 symbols", label.text)
    }

    private fun labelOf(bar: IndexStatusBar): JBLabel {
        val field = IndexStatusBar::class.java.getDeclaredField("label")
        field.isAccessible = true
        return field.get(bar) as JBLabel
    }
}
