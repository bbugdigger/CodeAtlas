package com.bugdigger.codeatlas.index

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicReference

/**
 * Project-level service that owns the in-memory index.
 *
 * Phase 1 Week 1: scaffold only. Holds the list of extracted [CodeChunk]s and exposes a
 * [requestFullIndex] entry point. Embedding, persistence, and search are added in Week 2.
 */
@Service(Service.Level.PROJECT)
class CodeAtlasIndexService(private val project: Project) {

    private val state = AtomicReference<IndexState>(IndexState.Empty)

    val chunkCount: Int
        get() = (state.get() as? IndexState.Ready)?.chunks?.size ?: 0

    fun snapshotChunks(): List<CodeChunk> =
        (state.get() as? IndexState.Ready)?.chunks.orEmpty()

    /** Kicks off a full index build under a [Task.Backgroundable] with progress UI. */
    fun requestFullIndex() {
        val task = object : Task.Backgroundable(project, "CodeAtlas: building index", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "CodeAtlas — extracting symbols"
                val chunks = IndexBuilder(project).buildAllChunks(indicator)
                state.set(IndexState.Ready(chunks))
            }
        }
        ProgressManager.getInstance().run(task)
    }

    /** Synchronous build for tests. */
    internal fun buildForTests(indicator: ProgressIndicator): List<CodeChunk> {
        val chunks = IndexBuilder(project).buildAllChunks(indicator)
        state.set(IndexState.Ready(chunks))
        return chunks
    }

    private sealed interface IndexState {
        data object Empty : IndexState
        data class Ready(val chunks: List<CodeChunk>) : IndexState
    }
}
