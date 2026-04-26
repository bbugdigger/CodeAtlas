package com.bugdigger.codeatlas.mcp.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCodeToolTest {

    @Test
    fun rejectsMissingQuery() = runBlocking {
        val tool = SearchCodeTool { stub() }
        val result = tool.handle(buildJsonObject { })
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("query"))
    }

    @Test
    fun rejectsBlankQuery() = runBlocking {
        val tool = SearchCodeTool { stub() }
        val result = tool.handle(buildJsonObject { put("query", JsonPrimitive("   ")) })
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun returnsFailureWhenNoProject() = runBlocking {
        val tool = SearchCodeTool { null }
        val result = tool.handle(buildJsonObject { put("query", JsonPrimitive("foo")) })
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("project"))
    }

    @Test
    fun returnsFailureWhenIndexNotReady() = runBlocking {
        val backend = stub(ready = false)
        val tool = SearchCodeTool { backend }
        val result = tool.handle(buildJsonObject { put("query", JsonPrimitive("foo")) })
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).message.contains("not built"))
    }

    @Test
    fun usesDefaultLimitAndSnippetWhenAbsent() = runBlocking {
        val backend = stub()
        val tool = SearchCodeTool { backend }
        tool.handle(buildJsonObject { put("query", JsonPrimitive("foo")) })
        assertEquals(SearchCodeTool.DEFAULT_LIMIT, backend.lastLimit)
        assertEquals(SearchCodeTool.DEFAULT_INCLUDE_SNIPPET, backend.lastIncludeSnippet)
    }

    @Test
    fun coercesLimitIntoValidRange() = runBlocking {
        val backend = stub()
        val tool = SearchCodeTool { backend }
        tool.handle(buildJsonObject {
            put("query", JsonPrimitive("foo"))
            put("limit", JsonPrimitive(9999))
        })
        assertEquals(SearchCodeTool.MAX_LIMIT, backend.lastLimit)

        tool.handle(buildJsonObject {
            put("query", JsonPrimitive("foo"))
            put("limit", JsonPrimitive(0))
        })
        assertEquals(SearchCodeTool.MIN_LIMIT, backend.lastLimit)
    }

    @Test
    fun honorsIncludeSnippetFalse() = runBlocking {
        val backend = stub(results = listOf(dto(snippet = "fun x() = 1")))
        val tool = SearchCodeTool { backend }
        val result = tool.handle(buildJsonObject {
            put("query", JsonPrimitive("foo"))
            put("include_snippet", JsonPrimitive(false))
        })
        assertEquals(false, backend.lastIncludeSnippet)
        val json = (result as ToolResult.Success).textBlocks.single()
        // Snippet is dropped from JSON when null due to encodeDefaults = false.
        assertFalse("snippet should not be encoded when include_snippet=false",
            json.contains("\"snippet\""))
    }

    @Test
    fun encodesResultsAsJsonArray() = runBlocking {
        val backend = stub(results = listOf(
            dto(qualifiedName = "demo.Foo.bar", path = "src/Foo.kt", score = 0.9f, snippet = "hi"),
            dto(qualifiedName = "demo.Baz.qux", path = "src/Baz.kt", score = 0.7f),
        ))
        val tool = SearchCodeTool { backend }
        val result = tool.handle(buildJsonObject { put("query", JsonPrimitive("foo")) })

        val text = (result as ToolResult.Success).textBlocks.single()
        val parsed = Json.parseToJsonElement(text).jsonArray
        assertEquals(2, parsed.size)
        assertEquals("demo.Foo.bar", parsed[0].jsonObject["qualifiedName"]!!.jsonPrimitive.content)
        assertEquals("hi", parsed[0].jsonObject["snippet"]!!.jsonPrimitive.content)
        assertNull(parsed[1].jsonObject["snippet"])
    }

    private fun dto(
        path: String = "src/Foo.kt",
        qualifiedName: String = "demo.Foo",
        kind: String = "FUNCTION",
        signature: String = "fun foo()",
        language: String = "kotlin",
        score: Float = 0.5f,
        startOffset: Int = 0,
        endOffset: Int = 10,
        snippet: String? = null,
    ) = SearchResultDto(path, qualifiedName, kind, signature, language, score, startOffset, endOffset, snippet)

    private fun stub(
        ready: Boolean = true,
        results: List<SearchResultDto> = emptyList(),
    ) = StubBackend(ready, results)

    private class StubBackend(
        ready: Boolean,
        private val results: List<SearchResultDto>,
    ) : SearchCodeTool.Backend {
        override val isReady: Boolean = ready
        var lastLimit: Int = -1
        var lastIncludeSnippet: Boolean = false

        override suspend fun search(query: String, limit: Int, includeSnippet: Boolean): List<SearchResultDto> {
            lastLimit = limit
            lastIncludeSnippet = includeSnippet
            // Mirror the real backend's contract: drop snippets when the caller asks to.
            return if (includeSnippet) results else results.map { it.copy(snippet = null) }
        }
    }
}
