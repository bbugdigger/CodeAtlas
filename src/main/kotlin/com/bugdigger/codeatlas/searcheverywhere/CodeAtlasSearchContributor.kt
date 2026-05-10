package com.bugdigger.codeatlas.searcheverywhere

import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.IndexState
import com.bugdigger.codeatlas.search.RankedResult
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.util.Processor
import javax.swing.ListCellRenderer

/**
 * Search Everywhere contributor that exposes CodeAtlas's semantic index as a
 * dedicated "Code Atlas" tab (Shift-Shift). Replaces the right-side tool window.
 *
 * Each call to [fetchElements] runs the same retrieval pipeline used elsewhere
 * in the plugin (`CodeAtlasIndexService.search`). [getElementPriority] maps each
 * [RankedResult]'s `finalScore` (0..1) to an integer priority (0..10_000) so the
 * platform's natural ordering reflects our re-ranked output.
 *
 * Implements the non-weighted [SearchEverywhereContributor] rather than the
 * weighted variant: the latter's primary fetch method is marked
 * `@ApiStatus.Internal` and would fail Marketplace verification. The base
 * interface's [getElementPriority] hook is fully public and gives us the same
 * ordering control.
 *
 * The index state is reflected only via [getAdvertisement] (gray hint text under
 * the input). When the index is still building, this contributor returns no
 * results — the status-bar widget is the single source of truth for progress.
 */
class CodeAtlasSearchContributor(
    private val event: AnActionEvent,
    private val project: Project,
) : SearchEverywhereContributor<RankedResult> {

    override fun getSearchProviderId(): String = ID
    override fun getGroupName(): String = "Code Atlas"
    override fun getSortWeight(): Int = 1500
    override fun showInFindResults(): Boolean = false
    override fun isShownInSeparateTab(): Boolean = true

    override fun getElementsRenderer(): ListCellRenderer<in RankedResult> {
        @Suppress("UNCHECKED_CAST")
        return CodeAtlasSearchRenderer() as ListCellRenderer<in RankedResult>
    }

    override fun getAdvertisement(): String? {
        val state = project.service<CodeAtlasIndexService>().let { svc ->
            // We have no direct accessor for the latest IndexState, but chunkCount > 0
            // implies Ready. Anything else (Empty / BuildingFullIndex) is "not ready".
            if (svc.chunkCount > 0) IndexState.Ready(svc.chunkCount) else IndexState.Empty
        }
        return when (state) {
            is IndexState.Ready -> null
            else -> "Index is building — results will appear once it's ready."
        }
    }

    override fun fetchElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in RankedResult>,
    ) {
        if (pattern.isBlank()) return
        val service = project.service<CodeAtlasIndexService>()
        // Install the SE-supplied indicator on this thread so runBlockingCancellable can pick it up.
        // Without this, tests (and any caller that doesn't already have an indicator on the
        // thread context) would fail with "There is no ProgressIndicator or Job in this thread".
        val results = ProgressManager.getInstance().runProcess<List<RankedResult>>(
            { runBlockingCancellable { service.search(pattern, RESULT_LIMIT) } },
            progressIndicator,
        )
        // Results are already sorted by finalScore desc; getElementPriority keeps platform
        // ordering consistent with that when results are merged with other contributors.
        for (r in results) {
            progressIndicator.checkCanceled()
            if (!consumer.process(r)) return
        }
    }

    // The base method is annotated @Deprecated on the IntelliJ side, but it has no
    // public replacement and is still the documented hook for influencing item order
    // — and it is not @ApiStatus.Internal, so it passes Marketplace verification.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getElementPriority(element: RankedResult, searchPattern: String): Int =
        (element.finalScore.coerceIn(0f, 1f) * 10_000f).toInt()

    override fun processSelectedItem(
        selected: RankedResult,
        modifiers: Int,
        searchText: String,
    ): Boolean {
        val vfile = VirtualFileManager.getInstance().findFileByUrl(selected.chunk.virtualFileUrl)
            ?: return false
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, vfile, selected.chunk.startOffset).navigate(true)
        }
        return true
    }

    override fun dispose() = Unit

    companion object {
        const val ID = "CodeAtlasSearchEverywhereContributor"
        private const val RESULT_LIMIT = 30
    }
}

/**
 * Factory wired in `plugin.xml` via `<searchEverywhereContributor>`. Constructs a
 * fresh contributor per Search Everywhere invocation, scoped to the active project.
 */
class CodeAtlasSearchContributorFactory : SearchEverywhereContributorFactory<RankedResult> {
    override fun createContributor(initEvent: AnActionEvent): SearchEverywhereContributor<RankedResult> {
        val project = initEvent.project
            ?: error("CodeAtlasSearchContributor requires a project context")
        return CodeAtlasSearchContributor(initEvent, project)
    }
}
