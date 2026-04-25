package com.bugdigger.codeatlas.embedding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the defensive default of [EmbeddingProvider.embedOne].
 *
 * Older versions used `embed(listOf(text)).first()`, which threw
 * [NoSuchElementException] if the underlying provider returned an empty list
 * (an upstream init failure that had been swallowed). Now we fall back to a
 * zero vector so search degrades to "no hits" instead of crashing.
 */
class EmbeddingProviderDefaultsTest {

    @Test
    fun embedOneReturnsZeroVectorWhenEmbedReturnsEmpty() = runBlocking {
        val provider = object : EmbeddingProvider {
            override val dim: Int = 7
            override val modelId: String = "empty-test"
            override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
        }

        val v = provider.embedOne("anything")

        assertEquals(7, v.size)
        assertTrue("expected all-zero vector, got ${v.toList()}", v.all { it == 0f })
    }

    @Test
    fun embedOneReturnsFirstWhenEmbedReturnsNonEmpty() = runBlocking {
        val payload = floatArrayOf(0.1f, 0.2f, 0.3f)
        val provider = object : EmbeddingProvider {
            override val dim: Int = 3
            override val modelId: String = "single-test"
            override suspend fun embed(texts: List<String>): List<FloatArray> = listOf(payload)
        }

        val v = provider.embedOne("q")

        assertEquals(payload.toList(), v.toList())
    }
}
