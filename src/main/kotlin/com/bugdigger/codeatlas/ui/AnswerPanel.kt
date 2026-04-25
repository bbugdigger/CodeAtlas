package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.rag.AnswerToken
import com.bugdigger.codeatlas.rag.CitationParser
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Scrollable panel that renders a streaming RAG answer.
 *
 * Prose is accumulated into a [JTextPane] while citation segments are inserted as
 * inline clickable links that carry the 1-based citation index. Once the stream
 * terminates with [AnswerToken.Done], the backing [SourceCard]s are rendered in
 * order beneath the prose.
 *
 * All mutation methods marshal to the EDT; callers may invoke [consume] from any thread.
 */
class AnswerPanel(
    private val onCitationClick: (CodeChunk) -> Unit,
    private val onStop: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val textPane = JTextPane().apply {
        isEditable = false
        border = JBUI.Borders.empty(8)
    }
    private val sourcesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 8, 8)
    }
    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = JBColor.GRAY
        isVisible = false
    }
    private val copyButton = JButton("Copy").apply {
        toolTipText = "Copy the answer text to the clipboard"
        addActionListener { copyAnswer() }
    }
    private val stopButton = JButton("Stop").apply {
        toolTipText = "Stop the current answer generation"
        isVisible = false
        addActionListener {
            onStop()
            isVisible = false
        }
    }
    private val controls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
        add(copyButton)
        add(stopButton)
    }
    private val header = JPanel(BorderLayout()).apply {
        add(statusLabel, BorderLayout.WEST)
        add(controls, BorderLayout.EAST)
    }
    private val content = JPanel(BorderLayout()).apply {
        add(textPane, BorderLayout.NORTH)
        add(sourcesPanel, BorderLayout.CENTER)
    }
    private val scroll = JBScrollPane(content)

    private var parser = CitationParser()
    private var sources: List<CodeChunk> = emptyList()

    init {
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        textPane.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = handleTextClick(e)
            override fun mouseMoved(e: MouseEvent) = Unit
        })
    }

    /** Clears prose, sources, and internal parser state. Call before a new question. */
    fun reset() = onEdt {
        parser = CitationParser()
        sources = emptyList()
        textPane.text = ""
        sourcesPanel.removeAll()
        sourcesPanel.revalidate()
        sourcesPanel.repaint()
        statusLabel.isVisible = false
    }

    /** Displays a non-streaming informational message (e.g. "configure provider"). */
    fun showStatus(message: String, isError: Boolean = false) = onEdt {
        statusLabel.text = message
        statusLabel.foreground = if (isError) JBColor.RED else JBColor.GRAY
        statusLabel.isVisible = true
    }

    /** Toggle the Stop button's visibility while a generation is in flight. */
    fun setStreaming(active: Boolean): Unit = onEdt {
        stopButton.isVisible = active
    }

    /** Feeds one token from the [AnswerToken] stream. Safe from any thread. */
    fun consume(token: AnswerToken) = onEdt {
        when (token) {
            is AnswerToken.Delta -> {
                for (seg in parser.feed(token.text)) appendSegment(seg)
            }
            is AnswerToken.Done -> {
                for (seg in parser.finish()) appendSegment(seg)
                sources = token.sources
                renderSources()
                stopButton.isVisible = false
            }
            is AnswerToken.Error -> {
                for (seg in parser.finish()) appendSegment(seg)
                showStatus(token.message, isError = true)
                stopButton.isVisible = false
            }
        }
    }

    private fun copyAnswer() {
        val text = textPane.text.orEmpty()
        if (text.isBlank()) return
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        showStatus("Copied to clipboard.")
    }

    private fun appendSegment(seg: CitationParser.Segment) {
        val doc = textPane.styledDocument
        when (seg) {
            is CitationParser.Segment.Text -> {
                val attrs = SimpleAttributeSet()
                doc.insertString(doc.length, seg.value, attrs)
            }
            is CitationParser.Segment.Citation -> {
                val attrs = SimpleAttributeSet().apply {
                    StyleConstants.setForeground(this, JBColor.BLUE)
                    StyleConstants.setUnderline(this, true)
                    addAttribute(CITATION_ATTR, seg.index)
                }
                doc.insertString(doc.length, "[${seg.index}]", attrs)
            }
        }
    }

    private fun handleTextClick(e: MouseEvent) {
        val pos = textPane.viewToModel2D(e.point)
        if (pos < 0) return
        val doc = textPane.styledDocument
        val element = doc.getCharacterElement(pos)
        val idx = element.attributes.getAttribute(CITATION_ATTR) as? Int ?: return
        val chunk = sources.getOrNull(idx - 1) ?: return
        onCitationClick(chunk)
    }

    private fun renderSources() {
        sourcesPanel.removeAll()
        sources.forEachIndexed { i, chunk ->
            sourcesPanel.add(SourceCard(i + 1, chunk, onCitationClick))
        }
        sourcesPanel.revalidate()
        sourcesPanel.repaint()
        textPane.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
    }

    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }

    private companion object {
        const val CITATION_ATTR = "codeatlas.citation"
    }
}
