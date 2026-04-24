package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.CodeChunk

/**
 * Retrieval output consumed by the UI and (Phase 2) the RAG answer panel.
 * [finalScore] is the fused score used for ordering; [vectorScore] is kept for
 * debugging/tuning visibility.
 */
data class RankedResult(
    val chunk: CodeChunk,
    val finalScore: Float,
    val vectorScore: Float,
)
