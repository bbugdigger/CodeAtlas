package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.embedding.EmbeddingProvider
import com.bugdigger.codeatlas.embedding.OnnxEmbeddingProvider
import com.bugdigger.codeatlas.language.sha256Hex
import com.bugdigger.codeatlas.search.RankedResult
import com.bugdigger.codeatlas.search.Retriever
import com.bugdigger.codeatlas.search.StubIndexSignal
import com.bugdigger.codeatlas.search.VectorStore
import com.bugdigger.codeatlas.settings.CodeAtlasSettingsService
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

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
class CodeAtlasIndexService(private val project: Project) : Disposable {

    internal var embedder: EmbeddingProvider = OnnxEmbeddingProvider()
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

    /** True while a full-index task is queued or running. Single-flight gate. */
    private val buildInFlight = AtomicBoolean(false)

    /** Coroutine scope for the debounced cache flush. Cancelled on [dispose]. */
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingFlush: Job? = null

    val chunkCount: Int get() = synchronized(lock) { chunks.size }

    /**
     * Schedule a background full build with progress UI. Non-blocking.
     *
     * Single-flight: if a previous build is still queued or running, this call
     * is a no-op. Prevents the startup activity and a manual "Rebuild Index"
     * action from racing each other into two concurrent walks of the project,
     * which would otherwise compete for the same cache file on save.
     */
    fun requestFullIndex() {
        if (!buildInFlight.compareAndSet(false, true)) return
        val task = object : Task.Backgroundable(project, "CodeAtlas: building index", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    runBlocking { ensureIndexed(indicator) }
                } finally {
                    buildInFlight.set(false)
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    suspend fun search(query: String, limit: Int): List<RankedResult> {
        val (hasEntries, snapshot) = synchronized(lock) { (chunks.isNotEmpty()) to store }
        if (!hasEntries) return emptyList()
        return Retriever(embedder, snapshot, StubIndexSignal(project)).search(query, limit)
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
     * Replace the chunks/vectors for a single file in memory and schedule a
     * debounced cache flush. Called by [CodeAtlasPsiChangeListener] after its
     * 1 s coalescing window.
     *
     * The disk write is intentionally NOT synchronous here — the in-memory
     * index is authoritative for retrieval, and rewriting a multi-megabyte
     * cache file on every keystroke makes editing feel sluggish. We coalesce
     * flushes via [scheduleCacheFlush]; a final flush runs on [dispose].
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
        scheduleCacheFlush()
        publish(IndexState.Ready(chunkCount))
    }

    /**
     * Wipe the on-disk cache for the current embedder and reset the in-memory
     * index. Triggers a fresh background rebuild so the user is not left with
     * an empty index. Intended for the "Clear Cache" action; cancels any
     * pending debounced flush so a stale write can't recreate the file behind
     * us.
     */
    fun clearCache() {
        pendingFlush?.cancel()
        runCatching { Files.deleteIfExists(cacheFilePath(embedder)) }
        synchronized(lock) {
            chunks = emptyList()
            vectors = emptyList()
            store = VectorStore(embedder.dim)
        }
        publish(IndexState.Empty)
        requestFullIndex()
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

    /**
     * Cancel any pending cache flush and schedule a fresh one [CACHE_FLUSH_DEBOUNCE_MS]
     * from now. Bursts of edits collapse into a single eventual write.
     */
    private fun scheduleCacheFlush() {
        pendingFlush?.cancel()
        pendingFlush = flushScope.launch {
            delay(CACHE_FLUSH_DEBOUNCE_MS)
            saveCacheNow()
        }
    }

    /** Snapshot the in-memory index under [lock] and write it to disk synchronously. */
    private fun saveCacheNow() {
        val (snapshotChunks, snapshotVectors) = synchronized(lock) {
            chunks.toList() to vectors.toList()
        }
        if (snapshotChunks.isEmpty()) return
        runCatching { cacheFor(embedder).save(snapshotChunks, snapshotVectors) }
    }

    override fun dispose() {
        // Ensure the most recent in-memory state hits disk before the project closes.
        // Cancel the pending debounced flush first to avoid a double-write race.
        pendingFlush?.cancel()
        flushScope.cancel()
        saveCacheNow()
    }

    private companion object {
        /** How long to coalesce file-edit bursts before rewriting the cache file. */
        const val CACHE_FLUSH_DEBOUNCE_MS = 30_000L
    }

    private fun cacheFor(provider: EmbeddingProvider): PersistentCache =
        PersistentCache(cacheFilePath(provider), provider.modelId, provider.dim)

    /**
     * Cache files are keyed by both project location AND embedder modelId.
     * That gives every provider its own file, so:
     *  - swapping embedders never overwrites the previous provider's cache;
     *  - swapping back is instant (the old cache loads via [PersistentCache.load]).
     * The in-header modelId/dim check inside [PersistentCache] stays as defense
     * in depth for the case where two providers happen to hash-collide on the
     * 8-char prefix used in the filename.
     */
    internal fun cacheFilePath(provider: EmbeddingProvider): Path {
        val projectKey = sha256Hex(project.locationHash).take(16)
        val modelKey = sha256Hex(provider.modelId).take(8)
        val filename = "index-$modelKey.bin"
        val overrideDir = project.service<CodeAtlasSettingsService>().cacheDirOverride
        return if (overrideDir != null) {
            Paths.get(overrideDir, projectKey, filename)
        } else {
            Paths.get(PathManager.getSystemPath(), "CodeAtlas", projectKey, filename)
        }
    }
}
