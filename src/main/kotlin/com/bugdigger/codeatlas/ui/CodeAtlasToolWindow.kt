package com.bugdigger.codeatlas.ui

import com.bugdigger.codeatlas.index.CODE_ATLAS_INDEX_TOPIC
import com.bugdigger.codeatlas.index.CodeAtlasIndexListener
import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.index.IndexState
import com.bugdigger.codeatlas.rag.AnswerToken
import com.bugdigger.codeatlas.rag.KoogAnswerGenerator
import com.bugdigger.codeatlas.search.RankedResult
import com.bugdigger.codeatlas.settings.CodeAtlasSettingsService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Root view for the CodeAtlas tool window.
 *
 * Owns the search bar, result list, and status strip, and coordinates search
 * requests. Every user keystroke that clears the debounce cancels any
 * in-flight [searchJob] — so the UI never renders stale results.
 *
 * The center uses a [JBSplitter] with an empty bottom component reserved for
 * the Phase-2 RAG answer panel; in Phase 1 the divider sits at `1.0` and the
 * bottom is invisible.
 */
class CodeAtlasToolWindow(
    private val project: Project,
    parent: Disposable,
) : Disposable {

    private val listModel = DefaultListModel<RankedResult>()
    private val resultList = JBList(listModel).apply {
        cellRenderer = ResultListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No results yet. Wait for indexing, then search."
    }
    private val statusBar = IndexStatusBar()
    private val answerPanel = AnswerPanel(::navigateToChunk)
    private val searchBar = SearchBar("Ask about the codebase…", ::onSearch, ::onAsk)
    private val findUsagesController = FindUsagesController(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val splitter = JBSplitter(true, 1.0f).apply {
        firstComponent = JBScrollPane(resultList)
        secondComponent = answerPanel
        dividerWidth = 1
    }

    @Volatile private var searchJob: Job? = null
    @Volatile private var answerJob: Job? = null

    val component: JComponent = buildRoot()

    init {
        Disposer.register(parent, this)

        val service = project.service<CodeAtlasIndexService>()
        val initialCount = service.chunkCount
        statusBar.update(if (initialCount > 0) {
            IndexState.Ready(initialCount)
        } else {
            service.requestFullIndex()
            IndexState.Empty
        })

        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val idx = resultList.locationToIndex(e.point)
                if (idx >= 0) {
                    resultList.selectedIndex = idx
                    navigate()
                }
            }
            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
        resultList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    navigate()
                    e.consume()
                }
            }
        })

        project.messageBus.connect(this).subscribe(
            CODE_ATLAS_INDEX_TOPIC,
            CodeAtlasIndexListener { state ->
                SwingUtilities.invokeLater { statusBar.update(state) }
            },
        )
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun buildRoot(): JComponent {
        return JPanel(BorderLayout()).apply {
            add(searchBar, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(statusBar, BorderLayout.SOUTH)
        }
    }

    private fun onSearch(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            SwingUtilities.invokeLater { listModel.clear() }
            return
        }
        searchJob = scope.launch {
            val results = project.service<CodeAtlasIndexService>().search(query, RESULT_LIMIT)
            SwingUtilities.invokeLater {
                listModel.clear()
                for (r in results) listModel.addElement(r)
            }
        }
    }

    private fun onAsk(query: String) {
        if (query.isBlank()) return
        answerJob?.cancel()
        answerPanel.reset()
        SwingUtilities.invokeLater { splitter.proportion = 0.5f }

        val settings = project.service<CodeAtlasSettingsService>()
        val provider = settings.resolveProvider()
        if (provider == null) {
            answerPanel.showStatus(
                "Configure an LLM provider in Settings → Tools → CodeAtlas.",
                isError = true,
            )
            return
        }
        answerPanel.showStatus("Thinking…")

        answerJob = scope.launch {
            val results = project.service<CodeAtlasIndexService>().search(query, ANSWER_TOP_K)
            val chunks = results.map { it.chunk }
            val generator = KoogAnswerGenerator { provider }
            generator.generate(query, chunks).collect { token ->
                answerPanel.consume(token)
                if (token is AnswerToken.Delta) {
                    answerPanel.showStatus("Streaming…")
                } else if (token is AnswerToken.Done) {
                    answerPanel.showStatus("Done.")
                }
            }
        }
    }

    private fun navigate() {
        val selected = resultList.selectedValue ?: return
        navigateToChunk(selected.chunk)
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val idx = resultList.locationToIndex(e.point)
        if (idx < 0 || !resultList.getCellBounds(idx, idx).contains(e.point)) return
        resultList.selectedIndex = idx
        val sel = resultList.selectedValue ?: return
        val menu = JPopupMenu().apply {
            add(JMenuItem("Find usages").apply {
                addActionListener { findUsagesController.findUsagesFor(sel.chunk) }
            })
        }
        menu.show(resultList, e.x, e.y)
    }

    private fun navigateToChunk(chunk: CodeChunk) {
        val vfile = VirtualFileManager.getInstance().findFileByUrl(chunk.virtualFileUrl)
            ?: return
        OpenFileDescriptor(project, vfile, chunk.startOffset).navigate(true)
    }

    companion object {
        private const val RESULT_LIMIT = 20
        private const val ANSWER_TOP_K = 8
    }
}
