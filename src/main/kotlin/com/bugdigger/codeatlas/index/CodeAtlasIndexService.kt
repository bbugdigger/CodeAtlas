package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.embedding.EmbeddingProvider
import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.bugdigger.codeatlas.language.sha256Hex
import com.bugdigger.codeatlas.search.RankedResult
import com.bugdigger.codeatlas.search.Retriever
import com.bugdigger.codeatlas.search.VectorStore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Project-level service that owns the retrieval index.
 *
 * The [embedder] is a plain property so tests can swap in a deterministic
 * provider before triggering [rebuildForTests]. Production uses
 * [HashEmbeddingProvider] as the offline default in Phase 1; switch to
 * [com.bugdigger.codeatlas.embedding.OnnxEmbeddingProvider] once the
 * download/session path is verified (Week 4).
 */
@Service(Service.Level.PROJECT)
class CodeAtlasIndexService(private val project: Project) {

    internal var embedder: EmbeddingProvider = HashEmbeddingProvider()
        set(value) {
            // Swapping the embedder changes vector semantics and invalidates caches.
            field = value
            vectorStore = VectorStore(value.dim)
        }

    private var vectorStore: VectorStore = VectorStore(embedder.dim)

    val chunkCount: Int get() = vectorStore.entryCount

    /** Non-blocking: schedules a background build with a progress UI. */
    fun requestFullIndex() {
        val task = object : Task.Backgroundable(project, "CodeAtlas: building index", true) {
            override fun run(indicator: ProgressIndicator) {
                runBlocking { ensureIndexed(indicator) }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    /** Top-level query entry point. Returns empty list if no index is loaded. */
    suspend fun search(query: String, limit: Int): List<RankedResult> {
        if (vectorStore.entryCount == 0) return emptyList()
        return Retriever(embedder, vectorStore).search(query, limit)
    }

    internal suspend fun ensureIndexed(indicator: ProgressIndicator) {
        val cache = cacheFor(embedder)
        cache.load()?.let { loaded ->
            replaceStore(loaded.chunks, loaded.vectors)
            return
        }
        val result = IndexBuilder(project, embedder).build(indicator)
        replaceStore(result.chunks, result.vectors)
        if (result.chunks.isNotEmpty()) {
            cache.save(result.chunks, result.vectors)
        }
    }

    /** Test helper: forces a fresh build and skips cache. */
    internal suspend fun rebuildForTests(indicator: ProgressIndicator): List<CodeChunk> {
        val result = IndexBuilder(project, embedder).build(indicator)
        replaceStore(result.chunks, result.vectors)
        return result.chunks
    }

    private fun replaceStore(chunks: List<CodeChunk>, vectors: List<FloatArray>) {
        val fresh = VectorStore(embedder.dim)
        fresh.addAll(chunks.zip(vectors))
        vectorStore = fresh
    }

    private fun cacheFor(provider: EmbeddingProvider): PersistentCache =
        PersistentCache(cacheFilePath(), provider.modelId, provider.dim)

    private fun cacheFilePath(): Path {
        val projectKey = sha256Hex(project.locationHash).take(16)
        return Paths.get(PathManager.getSystemPath(), "CodeAtlas", projectKey, "index.bin")
    }
}
