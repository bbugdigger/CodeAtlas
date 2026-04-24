package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.CodeChunk
import java.util.PriorityQueue

/**
 * In-memory vector store keyed by insertion order. Vectors are held in a flat
 * `FloatArray` of length `N * dim` for cache-friendly top-K scans.
 *
 * Linear scan is adequate up to ~10k chunks (sub-20ms on a modern laptop).
 * Replace with HNSW only after profiling shows it's a bottleneck.
 */
class VectorStore(val dim: Int) {

    private val chunks: MutableList<CodeChunk> = mutableListOf()
    private var vectors: FloatArray = FloatArray(0)

    val entryCount: Int get() = chunks.size

    fun clear() {
        chunks.clear()
        vectors = FloatArray(0)
    }

    /** Appends a batch. Each pair's vector must have length [dim]. */
    fun addAll(entries: List<Pair<CodeChunk, FloatArray>>) {
        if (entries.isEmpty()) return
        for ((_, v) in entries) require(v.size == dim) { "vector dim mismatch: expected $dim, got ${v.size}" }
        val prior = chunks.size
        val newVecs = vectors.copyOf((prior + entries.size) * dim)
        for ((i, e) in entries.withIndex()) {
            chunks += e.first
            System.arraycopy(e.second, 0, newVecs, (prior + i) * dim, dim)
        }
        vectors = newVecs
    }

    /** Returns up to [k] hits sorted by descending score. Assumes [query] is unit-norm. */
    fun topK(query: FloatArray, k: Int): List<Hit> {
        require(query.size == dim) { "query dim mismatch: expected $dim, got ${query.size}" }
        val n = chunks.size
        if (n == 0 || k <= 0) return emptyList()
        val heap = PriorityQueue<Hit>(k.coerceAtMost(n), compareBy { it.score })
        for (i in 0 until n) {
            val score = dot(query, vectors, i * dim)
            if (heap.size < k) {
                heap.add(Hit(i, chunks[i], score))
            } else if (score > heap.peek().score) {
                heap.poll()
                heap.add(Hit(i, chunks[i], score))
            }
        }
        return heap.toList().sortedByDescending { it.score }
    }

    private fun dot(q: FloatArray, store: FloatArray, offset: Int): Float {
        var s = 0f
        for (i in 0 until dim) s += q[i] * store[offset + i]
        return s
    }

    data class Hit(val index: Int, val chunk: CodeChunk, val score: Float)
}
