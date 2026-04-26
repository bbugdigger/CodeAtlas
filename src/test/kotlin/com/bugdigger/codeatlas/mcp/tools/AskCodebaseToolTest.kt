package com.bugdigger.codeatlas.mcp.tools

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.rag.AnswerToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskCodebaseToolTest {

    @Test
    fun rejectsMissingQuery() = runBlocking {
        val tool = AskCodebaseTool { stub() }
        val r = tool.handle(buildJsonObject { })
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).message.contains("query"))
    }

    @Test
    fun returnsFailureWhenNoProject() = runBlocking {
        val tool = AskCodebaseTool { null }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("how does X work")) })
        assertTrue(r is ToolResult.Failure)
    }

    @Test
    fun returnsFailureWhenIndexNotReady() = runBlocking {
        val tool = AskCodebaseTool { stub(ready = false) }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })
        assertTrue((r as ToolResult.Failure).message.contains("not built"))
    }

    @Test
    fun returnsFailureWhenProviderMissing() = runBlocking {
        val tool = AskCodebaseTool { stub(providerConfigured = false) }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })
        assertTrue((r as ToolResult.Failure).message.contains("LLM provider"))
    }

    @Test
    fun accumulatesDeltasIntoFinalAnswer() = runBlocking {
        val backend = stub(
            chunks = listOf(chunk("demo.Foo.bar")),
            tokens = listOf(
                AnswerToken.Delta("Hello "),
                AnswerToken.Delta("world. "),
                AnswerToken.Delta("Done."),
                AnswerToken.Done(listOf(chunk("demo.Foo.bar"))),
            ),
        )
        val tool = AskCodebaseTool { backend }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })

        val success = r as ToolResult.Success
        assertEquals(2, success.textBlocks.size)
        assertEquals("Hello world. Done.", success.textBlocks[0])
        val sources = Json.parseToJsonElement(success.textBlocks[1]).jsonArray
        assertEquals(1, sources.size)
        assertEquals("demo.Foo.bar", sources[0].jsonObject["qualifiedName"]!!.jsonPrimitive.content)
    }

    @Test
    fun emitsFailureWhenGeneratorErrors() = runBlocking {
        val backend = stub(
            chunks = listOf(chunk("demo.A")),
            tokens = listOf(
                AnswerToken.Delta("partial "),
                AnswerToken.Error("provider rejected key"),
            ),
        )
        val tool = AskCodebaseTool { backend }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })
        assertTrue(r is ToolResult.Failure)
        assertEquals("provider rejected key", (r as ToolResult.Failure).message)
    }

    @Test
    fun coercesTopKAndPassesToBackend() = runBlocking {
        val backend = stub()
        val tool = AskCodebaseTool { backend }
        tool.handle(buildJsonObject {
            put("query", JsonPrimitive("q"))
            put("top_k", JsonPrimitive(9999))
        })
        assertEquals(AskCodebaseTool.MAX_TOP_K, backend.lastTopK)
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        val backend = stub(
            tokens = listOf(AnswerToken.Delta("never delivered")),
            generateOverride = {
                flow {
                    throw CancellationException("host disconnected")
                }
            },
        )
        val tool = AskCodebaseTool { backend }
        var threw = false
        try {
            tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })
        } catch (ce: CancellationException) {
            threw = true
        }
        assertTrue("CancellationException should propagate, not be swallowed", threw)
    }

    @Test
    fun unexpectedExceptionBecomesFailure() = runBlocking {
        val backend = stub(
            generateOverride = {
                flow {
                    throw IllegalStateException("upstream blew up")
                }
            },
        )
        val tool = AskCodebaseTool { backend }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })
        assertTrue(r is ToolResult.Failure)
        assertTrue((r as ToolResult.Failure).message.contains("unexpected"))
    }

    @Test
    fun fallsBackToRetrievedChunksWhenDoneMissing() = runBlocking {
        val backend = stub(
            chunks = listOf(chunk("demo.A"), chunk("demo.B")),
            // Stream ends without an explicit Done token (defensive — should still produce sources).
            tokens = listOf(AnswerToken.Delta("answer")),
        )
        val tool = AskCodebaseTool { backend }
        val r = tool.handle(buildJsonObject { put("query", JsonPrimitive("q")) })
        val success = r as ToolResult.Success
        val sources = Json.parseToJsonElement(success.textBlocks[1]).jsonArray
        assertEquals(2, sources.size)
    }

    private fun stub(
        ready: Boolean = true,
        providerConfigured: Boolean = true,
        chunks: List<CodeChunk> = emptyList(),
        tokens: List<AnswerToken> = listOf(AnswerToken.Done(chunks)),
        generateOverride: ((List<CodeChunk>) -> Flow<AnswerToken>)? = null,
    ) = StubBackend(ready, providerConfigured, chunks, tokens, generateOverride)

    private class StubBackend(
        ready: Boolean,
        providerConfigured: Boolean,
        private val retrievedChunks: List<CodeChunk>,
        private val scriptedTokens: List<AnswerToken>,
        private val generateOverride: ((List<CodeChunk>) -> Flow<AnswerToken>)?,
    ) : AskCodebaseTool.Backend {

        override val isReady: Boolean = ready
        override val isProviderConfigured: Boolean = providerConfigured
        var lastTopK: Int = -1

        override suspend fun retrieveTopK(query: String, k: Int): List<CodeChunk> {
            lastTopK = k
            return retrievedChunks
        }

        override fun generate(query: String, chunks: List<CodeChunk>): Flow<AnswerToken> =
            generateOverride?.invoke(chunks) ?: flow {
                for (t in scriptedTokens) emit(t)
            }

        override fun toSourceDtos(chunks: List<CodeChunk>): List<SourceDto> = chunks.map {
            SourceDto(
                path = "src/${it.qualifiedName}.kt",
                qualifiedName = it.qualifiedName,
                kind = it.kind.name,
                signature = it.signature,
                language = it.language,
                startOffset = it.startOffset,
                endOffset = it.endOffset,
            )
        }
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
