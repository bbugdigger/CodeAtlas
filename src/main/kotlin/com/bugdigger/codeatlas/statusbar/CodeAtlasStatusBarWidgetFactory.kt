package com.bugdigger.codeatlas.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * Factory wired in `plugin.xml` via `<statusBarWidgetFactory>`. One widget per project.
 */
class CodeAtlasStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = CodeAtlasStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = "CodeAtlas Index"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = CodeAtlasStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
