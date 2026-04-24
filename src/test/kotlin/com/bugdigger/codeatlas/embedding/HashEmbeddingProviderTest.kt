package com.bugdigger.codeatlas.embedding

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class HashEmbeddingProviderTest {

    private val provider = HashEmbeddingProvider(dim = 128)

    @Test
    fun vectorsHaveConfiguredDim() = runBlocking {
        val v = provider.embedOne("hello world")
        assertEquals(128, v.size)
    }

    @Test
    fun outputIsDeterministic() = runBlocking {
        val a = provider.embedOne("authenticate user with token")
        val b = provider.embedOne("authenticate user with token")
        assertArraysEqual(a, b)
    }

    @Test
    fun outputIsL2Normalized() = runBlocking {
        val v = provider.embedOne("where is authentication implemented")
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x.toDouble() * x.toDouble() })
        assertTrue("expected unit norm, got $norm", abs(norm - 1.0) < 1e-5)
    }

    @Test
    fun distinctInputsProduceDistinctVectors() = runBlocking {
        val a = provider.embedOne("authenticate")
        val b = provider.embedOne("payment flow")
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun emptyStringProducesZeroOrUnitVector() = runBlocking {
        val v = provider.embedOne("")
        assertEquals(128, v.size)
        // All-zero is acceptable (no tokens → zero vector → normalization is a no-op).
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun batchMatchesOneAtATime() = runBlocking {
        val batch = provider.embed(listOf("foo", "bar baz", "qux"))
        val one = listOf(provider.embedOne("foo"), provider.embedOne("bar baz"), provider.embedOne("qux"))
        assertEquals(batch.size, one.size)
        for (i in batch.indices) assertArraysEqual(batch[i], one[i])
    }

    private fun assertArraysEqual(a: FloatArray, b: FloatArray) {
        assertEquals("length", a.size, b.size)
        for (i in a.indices) assertEquals("index $i", a[i], b[i], 0f)
    }
}
