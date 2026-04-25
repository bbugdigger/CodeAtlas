package com.bugdigger.codeatlas.embedding

/**
 * Converts text into a fixed-dimension unit-norm vector.
 *
 * Implementations must be thread-safe and return vectors of length [dim].
 * The [modelId] is persisted alongside cached vectors so that a provider swap
 * (or model upgrade) invalidates the cache automatically.
 */
interface EmbeddingProvider {
    val dim: Int
    val modelId: String

    suspend fun embed(texts: List<String>): List<FloatArray>

    /**
     * Convenience wrapper that survives both an empty input and a provider that
     * returns an empty list (e.g. ONNX init failure swallowed upstream). Callers
     * downstream score zero against a zero vector, gracefully producing no hits
     * instead of crashing the search pipeline.
     */
    suspend fun embedOne(text: String): FloatArray =
        embed(listOf(text)).firstOrNull() ?: FloatArray(dim)
}
