package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.embedding.EmbeddingProvider

/**
 * Orchestrates the search pipeline: embed the query, fetch top-K candidates
 * from the [VectorStore], re-rank with [ScoringSignals], return the final top-N.
 *
 * If a [stubIndexSignal] is provided, candidate chunks whose qualified name
 * matches an identifier the IDE's stub index resolves for the query receive a
 * `W_STUB` boost on top of their cosine similarity.
 */
class Retriever(
    private val embedder: EmbeddingProvider,
    private val store: VectorStore,
    private val stubIndexSignal: StubIndexSignal? = null,
) {

    suspend fun search(query: String, limit: Int): List<RankedResult> {
        if (query.isBlank() || limit <= 0 || store.entryCount == 0) return emptyList()
        val candidateCount = (limit * 3).coerceAtLeast(MIN_CANDIDATE_POOL)
        val queryVec = embedder.embedOne(query)
        val candidates = store.topK(queryVec, candidateCount)
        val stubBoosts = stubIndexSignal
            ?.computeBoosts(query, candidates.map { it.chunk })
            .orEmpty()
        return candidates
            .map { hit ->
                val boost = stubBoosts[hit.chunk.id] ?: 0f
                val finalScore = ScoringSignals.score(hit.chunk, hit.score, query, boost)
                RankedResult(hit.chunk, finalScore, hit.score)
            }
            .sortedByDescending { it.finalScore }
            .take(limit)
    }

    companion object {
        private const val MIN_CANDIDATE_POOL = 50
    }
}
