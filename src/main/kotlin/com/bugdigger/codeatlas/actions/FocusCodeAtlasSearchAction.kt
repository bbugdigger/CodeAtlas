package com.bugdigger.codeatlas.actions

import com.bugdigger.codeatlas.ui.CodeAtlasToolWindowAccess
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Open the CodeAtlas tool window and focus the search field.
 *
 * Power-user shortcut for "I want to type a query right now without reaching
 * for the mouse." No default keybinding so it doesn't conflict with anything;
 * users can assign one in `Keymap > Plugins > CodeAtlas`.
 */
class FocusCodeAtlasSearchAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<CodeAtlasToolWindowAccess>().focusSearch(project)
    }
}
