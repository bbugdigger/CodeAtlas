package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Compact row rendered below an answer: the `[n]` badge, kind icon, qualified name,
 * signature, and file name. Clicking the card fires [onClick] with the backing chunk.
 */
class SourceCard(
    private val index: Int,
    private val chunk: CodeChunk,
    private val onClick: (CodeChunk) -> Unit,
) : JPanel(BorderLayout(8, 0)) {

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(4, 8),
        )
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val badge = JBLabel("[$index]").apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyRight(4)
        }
        val icon = JBLabel(iconFor(chunk.kind))
        val name = JBLabel(chunk.qualifiedName).apply { font = font.deriveFont(Font.BOLD) }
        val sig = JBLabel(chunk.signature).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }
        val path = JBLabel(fileName(chunk.virtualFileUrl)).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }

        val west = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            add(badge, BorderLayout.WEST)
            add(icon, BorderLayout.CENTER)
        }
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(name)
            add(sig)
        }
        add(west, BorderLayout.WEST)
        add(center, BorderLayout.CENTER)
        add(path, BorderLayout.EAST)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick(chunk)
        })
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
