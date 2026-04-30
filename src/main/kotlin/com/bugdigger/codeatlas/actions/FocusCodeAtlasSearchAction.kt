package com.bugdigger.codeatlas.actions

import com.bugdigger.codeatlas.searcheverywhere.CodeAtlasSearchContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Open Search Everywhere on the "Code Atlas" tab.
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
        SearchEverywhereManager.getInstance(project)
            .show(CodeAtlasSearchContributor.ID, "", e)
    }
}
