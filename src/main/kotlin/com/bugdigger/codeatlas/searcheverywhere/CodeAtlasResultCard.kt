package com.bugdigger.codeatlas.searcheverywhere

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.search.RankedResult
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Row component rendered for each [RankedResult] in the Search Everywhere
 * "Code Atlas" tab. Lays out: [icon] [qualified name] [file name].
 */
class CodeAtlasResultCard : JPanel(BorderLayout(8, 0)) {

    private val iconLabel = JBLabel()
    private val nameLabel = JBLabel()
    private val pathLabel = JBLabel()

    init {
        border = JBUI.Borders.empty(4, 8)
        isOpaque = true

        add(iconLabel, BorderLayout.WEST)
        add(nameLabel, BorderLayout.CENTER)
        add(pathLabel, BorderLayout.EAST)
    }

    fun bind(result: RankedResult) {
        val chunk = result.chunk
        iconLabel.icon = iconFor(chunk.kind)
        nameLabel.text = chunk.qualifiedName
        pathLabel.text = fileName(chunk.virtualFileUrl)
    }

    fun applySelection(bg: Color, fg: Color, selected: Boolean) {
        background = bg
        nameLabel.foreground = fg
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
