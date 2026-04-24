package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.search.RankedResult
import java.awt.Component
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Reuses a single [ResultCard] instance for all rows — this is the standard
 * Swing cell-renderer pattern and avoids per-row allocation.
 */
class ResultListCellRenderer : ListCellRenderer<RankedResult> {

    private val card = ResultCard()

    override fun getListCellRendererComponent(
        list: JList<out RankedResult>,
        value: RankedResult?,
        index: Int,
        selected: Boolean,
        focused: Boolean,
    ): Component {
        value?.let { card.bind(it) }
        val bg = if (selected) list.selectionBackground else list.background
        val fg = if (selected) list.selectionForeground else list.foreground
        card.applySelection(bg, fg, selected)
        return card
    }
}
