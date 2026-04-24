package com.bugdigger.codeatlas.embedding

import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Deterministic, offline embedder used as the Phase-1 default and in unit tests.
 *
 * Tokenizes on non-word characters and scatters each token into 32 buckets with
 * signed contributions driven by a seeded LCG. The result is reproducible across
 * JVMs and platforms, which makes cached vectors portable.
 *
 * Retrieval quality is low compared to a real code embedding model — identical
 * queries still match identical chunks, but paraphrases ("auth" vs. "login") will
 * not align. Swap in [OnnxEmbeddingProvider] in Week 4 once the model download
 * path is verified end-to-end.
 */
class HashEmbeddingProvider(override val dim: Int = 384) : EmbeddingProvider {

    override val modelId: String = "hash-v1:$dim"

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { embedText(it) }

    private fun embedText(text: String): FloatArray {
        val vec = FloatArray(dim)
        for (token in tokenize(text)) {
            var state = seedFor(token)
            repeat(PROJECTIONS_PER_TOKEN) {
                state = state * LCG_MUL + LCG_ADD
                val bucket = Math.floorMod(state ushr 1, dim.toLong()).toInt()
                val sign = if ((state and 1L) == 0L) 1f else -1f
                vec[bucket] += sign
            }
        }
        return normalize(vec)
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(TOKEN_SPLIT).filter { it.isNotEmpty() }

    private fun seedFor(token: String): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(token.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[i].toLong() and 0xff)
        return v
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    companion object {
        private const val LCG_MUL = 6364136223846793005L
        private const val LCG_ADD = 1442695040888963407L
        private const val PROJECTIONS_PER_TOKEN = 32
        private val TOKEN_SPLIT = Regex("[^a-z0-9]+")
    }
}
