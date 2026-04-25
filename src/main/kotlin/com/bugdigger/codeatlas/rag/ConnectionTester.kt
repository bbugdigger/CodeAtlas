package com.bugdigger.codeatlas.rag

import kotlinx.coroutines.flow.first

/**
 * Probes an [LlmProvider] with a tiny request and reports whether the credentials,
 * endpoint, and model id are working. Reuses [KoogAnswerGenerator] so the test
 * exercises the exact same client construction the production answer flow uses.
 *
 * Returns [Result.success] on the first non-error token (we don't need a complete
 * answer; receiving any delta or done marker proves the request succeeded).
 * On any error, returns [Result.failure] with the redacted message attached.
 */
object ConnectionTester {

    /** Single-token probe budget — enough to authenticate without burning credits. */
    private const val PROBE_QUERY = "ping"

    suspend fun test(provider: LlmProvider): Result<Unit> = runCatching {
        val generator = KoogAnswerGenerator(providerSupplier = { provider })
        val first = generator.generate(PROBE_QUERY, emptyList()).first()
        when (first) {
            is AnswerToken.Error -> error(first.message)
            is AnswerToken.Delta, is AnswerToken.Done -> Unit
        }
    }
}
