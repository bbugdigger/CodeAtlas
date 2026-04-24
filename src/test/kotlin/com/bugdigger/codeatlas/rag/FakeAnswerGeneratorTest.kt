package com.bugdigger.codeatlas.rag

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAnswerGeneratorTest {

    @Test
    fun emitsScriptedDeltasThenDone() = runBlocking {
        val chunk = chunk("demo.Foo.bar")
        val gen = FakeAnswerGenerator(listOf("hello ", "world [1]"))
        val tokens = gen.generate("q", listOf(chunk)).toList()
        assertEquals(
            listOf(
                AnswerToken.Delta("hello "),
                AnswerToken.Delta("world [1]"),
                AnswerToken.Done(listOf(chunk)),
            ),
            tokens,
        )
    }

    @Test
    fun emitsErrorWhenConfigured() = runBlocking {
        val gen = FakeAnswerGenerator(listOf("partial "), errorMessage = "boom")
        val tokens = gen.generate("q", emptyList()).toList()
        assertEquals(2, tokens.size)
        assertTrue(tokens[0] is AnswerToken.Delta)
        assertEquals(AnswerToken.Error("boom"), tokens[1])
    }

    private fun chunk(qn: String): CodeChunk = CodeChunk(
        id = qn,
        qualifiedName = qn,
        kind = ChunkKind.FUNCTION,
        signature = "fun x()",
        docComment = null,
        language = "kotlin",
        virtualFileUrl = "file:///x.kt",
        startOffset = 0,
        endOffset = 1,
        containerFqn = null,
        contentHash = "h",
    )
}
