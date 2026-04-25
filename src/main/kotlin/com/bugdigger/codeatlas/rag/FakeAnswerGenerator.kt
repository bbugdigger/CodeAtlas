package com.bugdigger.codeatlas.rag

import com.bugdigger.codeatlas.index.CodeChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic [AnswerGenerator] for tests and offline demos.
 *
 * Emits each element of [scriptedDeltas] as an [AnswerToken.Delta] in order, then
 * terminates with [AnswerToken.Done] listing the provided chunks (or [AnswerToken.Error]
 * if [errorMessage] is set).
 */
class FakeAnswerGenerator(
    private val scriptedDeltas: List<String>,
    private val errorMessage: String? = null,
) : AnswerGenerator {

    override fun generate(query: String, chunks: List<CodeChunk>): Flow<AnswerToken> = flow {
        for (d in scriptedDeltas) emit(AnswerToken.Delta(d))
        if (errorMessage != null) {
            emit(AnswerToken.Error(errorMessage))
        } else {
            emit(AnswerToken.Done(chunks))
        }
    }
}
