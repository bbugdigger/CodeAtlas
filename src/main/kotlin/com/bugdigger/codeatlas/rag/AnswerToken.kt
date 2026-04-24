package com.bugdigger.codeatlas.rag

import com.bugdigger.codeatlas.index.CodeChunk

/**
 * Events emitted by an [AnswerGenerator] as an LLM produces a grounded answer.
 *
 * A well-formed stream has zero or more [Delta] tokens, followed by exactly one of
 * [Done] or [Error]. Consumers must tolerate an [Error] arriving after some [Delta]s
 * (partial answer) and keep any already-rendered prose visible.
 */
sealed class AnswerToken {
    /** Incremental assistant text. [text] may be any size, including a single character. */
    data class Delta(val text: String) : AnswerToken()

    /**
     * Terminal success marker. [sources] is the ordered list of chunks that back the
     * `[1]..[N]` citations used in the streamed prose. Index in the list is 1-based
     * relative to the citation number (i.e., `sources[0]` is `[1]`).
     */
    data class Done(val sources: List<CodeChunk>) : AnswerToken()

    /**
     * Terminal failure marker. [message] is already redacted of secrets and safe to
     * surface in the UI verbatim.
     */
    data class Error(val message: String) : AnswerToken()
}
