package com.bugdigger.codeatlas.ui

import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Text field that invokes [onSearch] either after a 300ms idle window (debounce)
 * or immediately on Enter. A single [Timer] is restarted on every keystroke,
 * so in-flight debounces are collapsed to the latest text state.
 */
class SearchBar(
    placeholder: String,
    private val onSearch: (String) -> Unit,
) : JPanel(BorderLayout()) {

    private val field = JBTextField().apply { emptyText.text = placeholder }
    private val timer = Timer(DEBOUNCE_MS) { fire() }.apply {
        isRepeats = false
        isCoalesce = true
    }

    init {
        border = JBUI.Borders.empty(6, 8)
        add(field, BorderLayout.CENTER)

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

    private fun fire() {
        onSearch(field.text.orEmpty())
    }

    companion object {
        private const val DEBOUNCE_MS = 300
    }
}
