package com.bugdigger.codeatlas.actions

import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Manually trigger a full reindex of the open project.
 *
 * Single-flight inside [CodeAtlasIndexService]: if a build is already running
 * (e.g. from the startup activity), this is a no-op rather than a duplicate
 * walk. Useful when a user thinks the index is stale and wants to force a
 * refresh without restarting the IDE.
 */
class RebuildIndexAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<CodeAtlasIndexService>().requestFullIndex()
    }
}
