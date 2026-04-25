package com.bugdigger.codeatlas.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Project-level holder for the live [CodeAtlasToolWindow] instance.
 *
 * The factory writes the reference here on construction and clears it on
 * disposal, giving editor actions and other entry points a single place to
 * call into the tool window without fishing through `ContentManager`.
 */
@Service(Service.Level.PROJECT)
class CodeAtlasToolWindowAccess {

    @Volatile
    internal var window: CodeAtlasToolWindow? = null

    /** Activate the tool window and run [query] in its search bar. */
    fun runSearch(project: Project, query: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate({ window?.runQuery(query) }, true, true)
    }

    companion object {
        const val TOOL_WINDOW_ID = "CodeAtlas"
    }
}
