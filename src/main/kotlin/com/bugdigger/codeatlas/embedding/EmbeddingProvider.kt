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

    suspend fun embedOne(text: String): FloatArray = embed(listOf(text)).first()
}
