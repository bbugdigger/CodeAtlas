package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.embedding.EmbeddingProvider
import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.bugdigger.codeatlas.language.sha256Hex
import com.bugdigger.codeatlas.search.RankedResult
import com.bugdigger.codeatlas.search.Retriever
import com.bugdigger.codeatlas.search.VectorStore
import com.bugdigger.codeatlas.settings.CodeAtlasSettingsService
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Project-level service that owns the retrieval index.
 *
 * Publishes [IndexState] updates to [CODE_ATLAS_INDEX_TOPIC] on every
 * transition (empty → building → ready, and ready ↔ updating for incremental
 * edits). UI components subscribe via the project message bus.
 *
 * Concurrency: [chunks]/[vectors]/[store] are guarded by [lock]; the store
 * reference is replaced atomically under the lock so search callers never
 * observe a partial update.
 */
@Service(Service.Level.PROJECT)
class CodeAtlasIndexService(private val project: Project) {

    internal var embedder: EmbeddingProvider = HashEmbeddingProvider()
        set(value) {
            field = value
            synchronized(lock) {
                chunks = emptyList()
                vectors = emptyList()
                store = VectorStore(value.dim)
            }
            publish(IndexState.Empty)
        }

    private val lock = Any()
    private var chunks: List<CodeChunk> = emptyList()
    private var vectors: List<FloatArray> = emptyList()
    private var store: VectorStore = VectorStore(embedder.dim)

    val chunkCount: Int get() = synchronized(lock) { chunks.size }

    /** Schedule a background full build with progress UI. Non-blocking. */
    fun requestFullIndex() {
        val task = object : Task.Backgroundable(project, "CodeAtlas: building index", true) {
            override fun run(indicator: ProgressIndicator) {
                runBlocking { ensureIndexed(indicator) }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    suspend fun search(query: String, limit: Int): List<RankedResult> {
        val (hasEntries, snapshot) = synchronized(lock) { (chunks.isNotEmpty()) to store }
        if (!hasEntries) return emptyList()
        return Retriever(embedder, snapshot).search(query, limit)
    }

    /** Load from cache or run a full build. Publishes [IndexState] throughout. */
    suspend fun ensureIndexed(indicator: ProgressIndicator) {
        val cache = cacheFor(embedder)
        cache.load()?.let { loaded ->
            install(loaded.chunks, loaded.vectors)
            publish(IndexState.Ready(loaded.chunks.size))
            return
        }
        publish(IndexState.BuildingFullIndex(0, 0))
        val includeTests = project.service<CodeAtlasSettingsService>().includeTestSources
        val result = IndexBuilder(project, embedder).build(indicator, includeTests) { done, total ->
            publish(IndexState.BuildingFullIndex(done, total))
        }
        install(result.chunks, result.vectors)
        if (result.chunks.isNotEmpty()) cache.save(result.chunks, result.vectors)
        publish(IndexState.Ready(result.chunks.size))
    }

    /**
     * Replace the chunks/vectors for a single file and flush the new state to
     * cache. Called by [com.bugdigger.codeatlas.index.CodeAtlasPsiChangeListener]
     * after its 1s coalescing window.
     */
    suspend fun onFileChanged(vfile: VirtualFile) {
        publish(IndexState.Updating(chunkCount))
        val updated = IndexBuilder(project, embedder).extractAndEmbedForFile(vfile)
        val fileUrl = vfile.url
        synchronized(lock) {
            val keptChunks = mutableListOf<CodeChunk>()
            val keptVectors = mutableListOf<FloatArray>()
            for (i in chunks.indices) {
                if (chunks[i].virtualFileUrl != fileUrl) {
                    keptChunks += chunks[i]
                    keptVectors += vectors[i]
                }
            }
            chunks = keptChunks + updated.chunks
            vectors = keptVectors + updated.vectors
            rebuildStoreLocked()
        }
        if (chunks.isNotEmpty()) cacheFor(embedder).save(chunks, vectors)
        publish(IndexState.Ready(chunkCount))
    }

    /** Test helper: forces a fresh build, skipping the cache. */
    internal suspend fun rebuildForTests(indicator: ProgressIndicator): List<CodeChunk> {
        val result = IndexBuilder(project, embedder).build(indicator)
        install(result.chunks, result.vectors)
        publish(IndexState.Ready(result.chunks.size))
        return result.chunks
    }

    /** Test helper: seed the in-memory index without walking the project. */
    internal fun seedForTests(c: List<CodeChunk>, v: List<FloatArray>) {
        install(c, v)
        publish(IndexState.Ready(c.size))
    }

    internal fun chunkSnapshot(): List<CodeChunk> = synchronized(lock) { chunks.toList() }

    private fun install(c: List<CodeChunk>, v: List<FloatArray>) {
        synchronized(lock) {
            chunks = c
            vectors = v
            rebuildStoreLocked()
        }
    }

    private fun rebuildStoreLocked() {
        val fresh = VectorStore(embedder.dim)
        fresh.addAll(chunks.zip(vectors))
        store = fresh
    }

    private fun publish(state: IndexState) {
        project.messageBus.syncPublisher(CODE_ATLAS_INDEX_TOPIC).stateChanged(state)
    }

    private fun cacheFor(provider: EmbeddingProvider): PersistentCache =
        PersistentCache(cacheFilePath(), provider.modelId, provider.dim)

    private fun cacheFilePath(): Path {
        val projectKey = sha256Hex(project.locationHash).take(16)
        val overrideDir = project.service<CodeAtlasSettingsService>().cacheDirOverride
        return if (overrideDir != null) {
            Paths.get(overrideDir, projectKey, "index.bin")
        } else {
            Paths.get(PathManager.getSystemPath(), "CodeAtlas", projectKey, "index.bin")
        }
    }
}
