package com.bugdigger.codeatlas.actions

import com.bugdigger.codeatlas.ui.CodeAtlasToolWindowAccess
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor

/**
 * Editor-popup action that drives the CodeAtlas tool window from the current editor:
 *  - If there's a non-empty selection, use it verbatim as the query.
 *  - Otherwise, extract the identifier surrounding the caret.
 *
 * Activates the tool window, fills the search bar, and runs the search immediately
 * (bypassing the SearchBar debounce). Disabled when no editor is in context or no
 * usable query can be extracted.
 */
class AskCodeAtlasAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val enabled = project != null && editor != null && extractQuery(editor) != null
        e.presentation.isEnabledAndVisible = enabled
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val query = extractQuery(editor) ?: return
        project.service<CodeAtlasToolWindowAccess>().runSearch(project, query)
    }

    companion object {

        private const val MAX_QUERY_LEN = 200

        internal fun extractQuery(editor: Editor): String? {
            val caret = editor.caretModel.currentCaret
            val selected = caret.selectedText
            if (!selected.isNullOrBlank()) {
                return selected.trim().take(MAX_QUERY_LEN)
            }
            val text = editor.document.charsSequence
            if (text.isEmpty()) return null
            val offset = caret.offset.coerceIn(0, text.length)
            var start = offset
            while (start > 0 && Character.isJavaIdentifierPart(text[start - 1])) start--
            var end = offset
            while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++
            if (start == end) return null
            return text.subSequence(start, end).toString()
        }
    }
}
