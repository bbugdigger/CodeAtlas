package com.bugdigger.codeatlas.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CodeAtlasToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val window = CodeAtlasToolWindow(project, toolWindow.disposable)
        val access = project.service<CodeAtlasToolWindowAccess>()
        access.window = window
        Disposer.register(window, Disposable { access.window = null })

        val content = ContentFactory.getInstance().createContent(window.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
