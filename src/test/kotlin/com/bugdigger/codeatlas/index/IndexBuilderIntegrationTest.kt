package com.bugdigger.codeatlas.index

import com.bugdigger.codeatlas.embedding.HashEmbeddingProvider
import com.bugdigger.codeatlas.search.Retriever
import com.bugdigger.codeatlas.search.VectorStore
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking

class IndexBuilderIntegrationTest : BasePlatformTestCase() {

    fun testIndexAndRetrieveKnownSymbols() = runBlocking {
        myFixture.configureByText(
            "AuthService.kt",
            """
            package demo.auth
            class AuthService {
                fun login(user: String, pass: String): Boolean = true
                fun logout() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "PaymentService.kt",
            """
            package demo.payment
            class PaymentService {
                fun charge(amount: Int) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "ToolWindowHook.kt",
            """
            package demo.ui
            class ToolWindowHook {
                fun openToolWindow() {}
            }
            """.trimIndent(),
        )

        val embedder = HashEmbeddingProvider(dim = 128)
        val builder = IndexBuilder(project, embedder)
        val built = builder.build(EmptyProgressIndicator(), includeTests = false)

        assertTrue("expected chunks to be indexed", built.chunks.isNotEmpty())
        assertEquals(built.chunks.size, built.vectors.size)

        val store = VectorStore(embedder.dim)
        store.addAll(built.chunks.zip(built.vectors))
        val retriever = Retriever(embedder, store)

        assertTop3Contains(
            retriever.search("where is login implemented", 3).map { it.chunk.qualifiedName },
            "demo.auth.AuthService.login",
        )
        assertTop3Contains(
            retriever.search("PaymentService charge amount", 3).map { it.chunk.qualifiedName },
            "demo.payment.PaymentService.charge",
        )
        assertTop3Contains(
            retriever.search("tool window open", 3).map { it.chunk.qualifiedName },
            "demo.ui.ToolWindowHook.openToolWindow",
        )
    }

    private fun assertTop3Contains(results: List<String>, expectedFqn: String) {
        assertTrue(
            "expected '$expectedFqn' in top-3, got $results",
            results.contains(expectedFqn),
        )
    }
}
