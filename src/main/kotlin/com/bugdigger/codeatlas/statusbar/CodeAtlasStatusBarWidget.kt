package com.bugdigger.codeatlas.statusbar

import com.bugdigger.codeatlas.index.CODE_ATLAS_INDEX_TOPIC
import com.bugdigger.codeatlas.index.CodeAtlasIndexListener
import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.IndexState
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

/**
 * IDE-wide status-bar widget reflecting CodeAtlas index state.
 *
 * Subscribes to [CODE_ATLAS_INDEX_TOPIC] and re-renders on every transition.
 * Click behavior:
 *  - When the index is [IndexState.Ready], opens Search Everywhere on the
 *    "Code Atlas" tab.
 *  - Otherwise, kicks off a full rebuild (single-flight via the service).
 *
 * Replaces the previous in-tool-window `IndexStatusBar` JLabel.
 */
class CodeAtlasStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    @Volatile
    private var state: IndexState = IndexState.Empty

    private var statusBar: StatusBar? = null

    override fun ID(): String = WIDGET_ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val service = project.service<CodeAtlasIndexService>()
        val seedCount = service.chunkCount
        state = if (seedCount > 0) IndexState.Ready(seedCount) else IndexState.Empty
        project.messageBus.connect(this).subscribe(
            CODE_ATLAS_INDEX_TOPIC,
            CodeAtlasIndexListener { newState ->
                state = newState
                ApplicationManager.getApplication().invokeLater {
                    statusBar.updateWidget(WIDGET_ID)
                }
            },
        )
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = textFor(state)

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun getTooltipText(): String =
        "CodeAtlas index status. Click to search or rebuild."

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { mouseEvent ->
        if (state is IndexState.Ready) {
            // Delegate to the registered "Focus Search" action — that gives
            // SearchEverywhereManager.show() the AnActionEvent it needs.
            val action = ActionManager.getInstance().getAction("CodeAtlas.FocusSearchAction")
                ?: return@Consumer
            val component = statusBar?.component ?: mouseEvent.component
            val dataContext = DataManager.getInstance().getDataContext(component)
            val event = AnActionEvent.createFromInputEvent(
                mouseEvent,
                ActionPlaces.STATUS_BAR_PLACE,
                null,
                dataContext,
            )
            ActionUtil.performActionDumbAwareWithCallbacks(action, event)
        } else {
            project.service<CodeAtlasIndexService>().requestFullIndex()
        }
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val WIDGET_ID = "CodeAtlasStatusBarWidget"

        internal fun textFor(state: IndexState): String = when (state) {
            IndexState.Empty -> "CodeAtlas: no index"
            is IndexState.BuildingFullIndex -> "CodeAtlas: indexing ${state.done}/${state.total}"
            is IndexState.Updating -> "CodeAtlas: updating – ${state.count} symbols"
            is IndexState.Ready -> "CodeAtlas: ready · ${state.count} symbols"
        }
    }
}
