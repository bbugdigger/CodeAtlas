package com.bugdigger.codeatlas.rag

import com.bugdigger.codeatlas.index.CodeChunk
import kotlinx.coroutines.flow.Flow

/**
 * Produces a streamed, citation-grounded natural-language answer from a user query and
 * a ranked list of retrieved code chunks.
 *
 * Implementations MUST:
 *  - Emit zero or more [AnswerToken.Delta] before a terminal token.
 *  - Emit exactly one terminal [AnswerToken.Done] or [AnswerToken.Error].
 *  - Surface cancellation via standard coroutine cancellation; no token after cancel.
 *  - Never leak API keys or raw network errors in [AnswerToken.Error.message].
 */
interface AnswerGenerator {
    fun generate(query: String, chunks: List<CodeChunk>): Flow<AnswerToken>
}
