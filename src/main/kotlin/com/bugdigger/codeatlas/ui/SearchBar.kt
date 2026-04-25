package com.bugdigger.codeatlas.ui

import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Text field that invokes [onSearch] either after a 300ms idle window (debounce)
 * or immediately on Enter. A single [Timer] is restarted on every keystroke,
 * so in-flight debounces are collapsed to the latest text state.
 *
 * When [onAsk] is non-null, an "Ask" button is rendered on the trailing edge that
 * invokes [onAsk] with the current text. The button is independent of debounced
 * search and never triggers [onSearch].
 */
class SearchBar(
    placeholder: String,
    private val onSearch: (String) -> Unit,
    private val onAsk: ((String) -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val field = JBTextField().apply { emptyText.text = placeholder }
    private val timer = Timer(DEBOUNCE_MS) { fire() }.apply {
        isRepeats = false
        isCoalesce = true
    }

    init {
        border = JBUI.Borders.empty(6, 8)
        add(field, BorderLayout.CENTER)

        if (onAsk != null) {
            val button = JButton("Ask").apply {
                toolTipText = "Ask CodeAtlas using the configured LLM provider"
                addActionListener { onAsk.invoke(field.text.orEmpty()) }
            }
            add(button, BorderLayout.EAST)
        }

        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = timer.restart()
            override fun removeUpdate(e: DocumentEvent) = timer.restart()
            override fun changedUpdate(e: DocumentEvent) = timer.restart()
        })
        field.addActionListener {
            timer.stop()
            fire()
        }
    }

    /** External entry point: replace the field text and run a search immediately, bypassing debounce. */
    fun setTextAndSearch(text: String) {
        field.text = text
        timer.stop()
        fire()
    }

    /** Move keyboard focus into the text field (e.g. for the "Focus CodeAtlas Search" action). */
    fun requestFieldFocus() {
        field.requestFocusInWindow()
        field.selectAll()
    }

    private fun fire() {
        onSearch(field.text.orEmpty())
    }

    companion object {
        private const val DEBOUNCE_MS = 300
    }
}
