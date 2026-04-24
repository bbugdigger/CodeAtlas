package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.IndexState
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * Thin status strip at the bottom of the tool window. Reflects [IndexState]
 * published on [com.bugdigger.codeatlas.index.CODE_ATLAS_INDEX_TOPIC].
 */
class IndexStatusBar : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {

    private val label = JBLabel().apply { foreground = JBColor.GRAY }

    init {
        border = JBUI.Borders.empty(4, 8)
        add(label)
        update(IndexState.Empty)
    }

    fun update(state: IndexState) {
        label.text = when (state) {
            IndexState.Empty -> "No index yet"
            is IndexState.BuildingFullIndex -> "Indexing ${state.done}/${state.total}"
            is IndexState.Updating -> "Updating – ${state.count} symbols"
            is IndexState.Ready -> "Ready · ${state.count} symbols"
        }
    }
}
