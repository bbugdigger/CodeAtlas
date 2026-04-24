package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.search.RankedResult
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Row component rendered for each [RankedResult] in the tool-window result list.
 * Lays out: [icon] [qualified name + signature stacked] [file name].
 */
class ResultCard : JPanel(BorderLayout(8, 0)) {

    private val iconLabel = JBLabel()
    private val nameLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    private val sigLabel = JBLabel()
    private val pathLabel = JBLabel().apply { font = font.deriveFont(font.size2D - 1f) }

    init {
        border = JBUI.Borders.empty(4, 8)
        isOpaque = true

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(nameLabel)
            add(sigLabel)
        }
        add(iconLabel, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(pathLabel, BorderLayout.EAST)
    }

    fun bind(result: RankedResult) {
        val chunk = result.chunk
        iconLabel.icon = iconFor(chunk.kind)
        nameLabel.text = chunk.qualifiedName
        sigLabel.text = chunk.signature
        pathLabel.text = fileName(chunk.virtualFileUrl)
    }

    fun applySelection(bg: java.awt.Color, fg: java.awt.Color, selected: Boolean) {
        background = bg
        nameLabel.foreground = fg
        sigLabel.foreground = if (selected) fg else JBColor.GRAY
        pathLabel.foreground = if (selected) fg else JBColor.GRAY
    }

    private fun iconFor(kind: ChunkKind): Icon = when (kind) {
        ChunkKind.CLASS -> AllIcons.Nodes.Class
        ChunkKind.INTERFACE -> AllIcons.Nodes.Interface
        ChunkKind.OBJECT -> AllIcons.Nodes.Class
        ChunkKind.ENUM -> AllIcons.Nodes.Enum
        ChunkKind.ANNOTATION -> AllIcons.Nodes.Annotationtype
        ChunkKind.METHOD -> AllIcons.Nodes.Method
        ChunkKind.FUNCTION -> AllIcons.Nodes.Function
        ChunkKind.CONSTRUCTOR -> AllIcons.Nodes.ClassInitializer
        ChunkKind.DOC -> AllIcons.FileTypes.Text
    }

    private fun fileName(url: String): String {
        val slash = url.lastIndexOf('/')
        return if (slash >= 0) url.substring(slash + 1) else url
    }
}
