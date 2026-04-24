package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk

/**
 * Pure-function signal fusion for re-ranking.
 *
 * Final score =  `W_VECTOR * vectorScore`
 *              + `W_NAME   * identifierMatch(chunk, query)`
 *              + `W_KIND   * kindFit(chunk, query)`
 *              + `W_DOC    * (chunk has doc comment ? 1 : 0)`
 *
 * Weights are plain constants. Iterate them against a hand-labeled eval set.
 */
object ScoringSignals {

    const val W_VECTOR = 0.70f
    const val W_NAME = 0.15f
    const val W_KIND = 0.05f
    const val W_DOC = 0.10f

    fun score(chunk: CodeChunk, vectorScore: Float, query: String): Float {
        val name = identifierMatch(chunk, query)
        val kind = kindFit(chunk, query)
        val doc = if (!chunk.docComment.isNullOrBlank()) 1f else 0f
        return W_VECTOR * vectorScore + W_NAME * name + W_KIND * kind + W_DOC * doc
    }

    /** Fraction of query tokens (length ≥ 3) that appear in the chunk's name or signature. */
    fun identifierMatch(chunk: CodeChunk, query: String): Float {
        val tokens = query.lowercase().split(NON_WORD).filter { it.length >= 3 }
        if (tokens.isEmpty()) return 0f
        val haystack = (chunk.qualifiedName + " " + chunk.signature).lowercase()
        var hits = 0
        for (t in tokens) if (haystack.contains(t)) hits++
        return hits.toFloat() / tokens.size
    }

    /** Loose mapping of query intent to declaration kind. Intentionally coarse. */
    fun kindFit(chunk: CodeChunk, query: String): Float {
        val q = query.lowercase()
        return when (chunk.kind) {
            ChunkKind.CLASS, ChunkKind.OBJECT ->
                if ("class" in q || "where" in q) 1f else 0.6f
            ChunkKind.INTERFACE ->
                if ("interface" in q || "contract" in q) 1f else 0.5f
            ChunkKind.METHOD, ChunkKind.FUNCTION ->
                if ("how" in q || "does" in q || "handle" in q) 1f else 0.7f
            ChunkKind.CONSTRUCTOR ->
                if ("create" in q || "new" in q || "init" in q) 1f else 0.3f
            ChunkKind.ENUM -> 0.5f
            ChunkKind.ANNOTATION -> 0.2f
            ChunkKind.DOC -> 0.5f
        }
    }

    private val NON_WORD = Regex("[^a-z0-9]+")
}
