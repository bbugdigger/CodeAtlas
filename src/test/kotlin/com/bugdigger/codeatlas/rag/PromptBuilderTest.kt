package com.bugdigger.codeatlas.rag

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun numbersChunksInOrderAndEmbedsFilePath() {
        val prompt = PromptBuilder.build(
            query = "how does login work?",
            retrieved = listOf(
                RetrievedChunk(chunk("demo.Auth.login"), "fun login() { ... }"),
                RetrievedChunk(chunk("demo.Auth.logout"), "fun logout() { ... }"),
            ),
        )
        assertTrue(prompt.user.contains("[1] demo.Auth.login"))
        assertTrue(prompt.user.contains("[2] demo.Auth.logout"))
        assertTrue(prompt.user.contains("file: /src/Auth.kt"))
        assertEquals(2, prompt.includedChunks.size)
    }

    @Test
    fun systemPromptMarksSnippetsAsUntrusted() {
        val prompt = PromptBuilder.build("q", emptyList())
        assertTrue(prompt.system.contains("UNTRUSTED"))
    }

    @Test
    fun truncatesWhenBudgetExceeded() {
        val big = "x".repeat(3_000)
        val prompt = PromptBuilder.build(
            query = "q",
            retrieved = listOf(
                RetrievedChunk(chunk("a.A"), big),
                RetrievedChunk(chunk("b.B"), big),
                RetrievedChunk(chunk("c.C"), big),
            ),
            chunkBudgetChars = 4_000,
        )
        // At most two small chunks fit in 4k; the third must be dropped.
        assertTrue(prompt.includedChunks.size in 1..2)
        assertFalse(prompt.user.contains("[3] c.C"))
    }

    @Test
    fun emptyRetrievalStillProducesUsableUserPrompt() {
        val prompt = PromptBuilder.build("q", emptyList())
        assertTrue(prompt.user.contains("no code snippets"))
        assertTrue(prompt.includedChunks.isEmpty())
    }

    private fun chunk(qn: String): CodeChunk = CodeChunk(
        id = qn,
        qualifiedName = qn,
        kind = ChunkKind.FUNCTION,
        signature = "fun ${qn.substringAfterLast('.')}()",
        docComment = null,
        language = "kotlin",
        virtualFileUrl = "file:///src/Auth.kt",
        startOffset = 0,
        endOffset = 10,
        containerFqn = qn.substringBeforeLast('.'),
        contentHash = "h",
    )
}
