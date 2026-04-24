package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.embedding.EmbeddingProvider

/**
 * Orchestrates the search pipeline: embed the query, fetch top-K candidates
 * from the [VectorStore], re-rank with [ScoringSignals], return the final top-N.
 */
class Retriever(
    private val embedder: EmbeddingProvider,
    private val store: VectorStore,
) {

    suspend fun search(query: String, limit: Int): List<RankedResult> {
        if (query.isBlank() || limit <= 0 || store.entryCount == 0) return emptyList()
        val candidateCount = (limit * 3).coerceAtLeast(MIN_CANDIDATE_POOL)
        val queryVec = embedder.embedOne(query)
        val candidates = store.topK(queryVec, candidateCount)
        return candidates
            .map { hit ->
                val finalScore = ScoringSignals.score(hit.chunk, hit.score, query)
                RankedResult(hit.chunk, finalScore, hit.score)
            }
            .sortedByDescending { it.finalScore }
            .take(limit)
    }

    companion object {
        private const val MIN_CANDIDATE_POOL = 50
    }
}
