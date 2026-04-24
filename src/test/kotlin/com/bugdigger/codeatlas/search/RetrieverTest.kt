package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrieverTest {

    private val embedder = HashEmbeddingProvider(dim = 128)

    @Test
    fun exactTokenMatchOutranksUnrelatedChunks() = runBlocking {
        val store = VectorStore(embedder.dim)
        val chunks = listOf(
            chunk("demo.AuthService.login", "fun login(user: String, pass: String): Boolean"),
            chunk("demo.PaymentService.charge", "fun charge(amount: Int)"),
            chunk("demo.Logger.log", "fun log(msg: String)"),
        )
        val vectors = embedder.embed(chunks.map { it.embeddingInput() })
        store.addAll(chunks.zip(vectors))

        val retriever = Retriever(embedder, store)
        val results = retriever.search("login user", limit = 3)

        assertEquals(3, results.size)
        assertEquals("demo.AuthService.login", results[0].chunk.qualifiedName)
        // Ordering monotonicity on the fused score.
        for (i in 1 until results.size) {
            assertTrue(results[i - 1].finalScore >= results[i].finalScore)
        }
    }

    @Test
    fun emptyStoreYieldsEmptyResults() = runBlocking {
        val store = VectorStore(embedder.dim)
        val retriever = Retriever(embedder, store)
        assertTrue(retriever.search("anything", 5).isEmpty())
    }

    @Test
    fun blankQueryYieldsEmptyResults() = runBlocking {
        val store = VectorStore(embedder.dim)
        store.addAll(
            listOf(
                chunk("demo.A", "class A") to embedder.embedOne("demo.A")
            )
        )
        val retriever = Retriever(embedder, store)
        assertTrue(retriever.search("   ", 5).isEmpty())
    }

    private fun chunk(fq: String, sig: String): CodeChunk = CodeChunk(
        id = "t:$fq",
        qualifiedName = fq,
        kind = ChunkKind.METHOD,
        signature = sig,
        docComment = null,
        language = "kotlin",
        virtualFileUrl = "file:///$fq.kt",
        startOffset = 0,
        endOffset = 1,
        containerFqn = null,
        contentHash = "h:$fq",
    )
}
