package com.bugdigger.codeatlas.actions

import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages

/**
 * Delete the persistent index cache for the current project + embedder, then
 * trigger a fresh rebuild. Intended for the rare case where the cache is
 * suspected to be corrupted or out of sync.
 *
 * Confirms before acting because the rebuild can take tens of seconds on a
 * large project; users who hit the action by accident shouldn't pay that cost.
 */
class ClearCacheAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val choice = Messages.showYesNoDialog(
            project,
            "Delete the CodeAtlas cache and rebuild the index from scratch?",
            "Clear CodeAtlas Cache",
            Messages.getQuestionIcon(),
        )
        if (choice == Messages.YES) {
            project.service<CodeAtlasIndexService>().clearCache()
        }
    }
}
