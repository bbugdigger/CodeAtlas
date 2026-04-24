package com.bugdigger.codeatlas.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CodeAtlasToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = CodeAtlasToolWindow(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(window.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
