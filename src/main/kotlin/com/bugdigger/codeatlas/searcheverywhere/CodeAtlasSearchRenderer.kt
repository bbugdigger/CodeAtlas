package com.bugdigger.codeatlas.searcheverywhere

import com.bugdigger.codeatlas.search.RankedResult
import java.awt.Component
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * Cell renderer for the Search Everywhere "Code Atlas" tab. Re-uses a single
 * [CodeAtlasResultCard] instance across rows.
 */
class CodeAtlasSearchRenderer : ListCellRenderer<Any> {

    private val card = CodeAtlasResultCard()

    override fun getListCellRendererComponent(
        list: JList<out Any>,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        if (value !is RankedResult) {
            return card
        }
        card.bind(value)
        val bg = if (isSelected) list.selectionBackground else list.background
        val fg = if (isSelected) list.selectionForeground else list.foreground
        card.applySelection(bg, fg, isSelected)
        return card
    }
}
