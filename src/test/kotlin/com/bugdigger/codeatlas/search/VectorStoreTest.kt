package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VectorStoreTest {

    @Test
    fun topKReturnsHighestCosineInDescendingOrder() {
        val store = VectorStore(dim = 3)
        store.addAll(
            listOf(
                stub("a") to floatArrayOf(1f, 0f, 0f),
                stub("b") to floatArrayOf(0f, 1f, 0f),
                stub("c") to unit(floatArrayOf(0.9f, 0.1f, 0f)),
                stub("d") to floatArrayOf(0f, 0f, 1f),
            )
        )
        val hits = store.topK(floatArrayOf(1f, 0f, 0f), k = 3)
        val names = hits.map { it.chunk.qualifiedName }
        assertEquals(listOf("a", "c", "b"), names)
        // Descending scores.
        for (i in 1 until hits.size) assertTrue(hits[i - 1].score >= hits[i].score)
    }

    @Test
    fun topKClampsToEntryCount() {
        val store = VectorStore(dim = 2)
        store.addAll(
            listOf(
                stub("x") to floatArrayOf(1f, 0f),
                stub("y") to floatArrayOf(0f, 1f),
            )
        )
        val hits = store.topK(floatArrayOf(1f, 0f), k = 10)
        assertEquals(2, hits.size)
    }

    @Test
    fun emptyStoreReturnsEmpty() {
        val store = VectorStore(dim = 4)
        assertTrue(store.topK(FloatArray(4), 5).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedDimThrows() {
        val store = VectorStore(dim = 3)
        store.addAll(listOf(stub("a") to floatArrayOf(1f, 0f)))
    }

    private fun stub(name: String): CodeChunk = CodeChunk(
        id = "t:$name",
        qualifiedName = name,
        kind = ChunkKind.FUNCTION,
        signature = "fun $name()",
        docComment = null,
        language = "kotlin",
        virtualFileUrl = "file:///$name.kt",
        startOffset = 0,
        endOffset = 1,
        containerFqn = null,
        contentHash = "h:$name",
    )

    private fun unit(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }
}
