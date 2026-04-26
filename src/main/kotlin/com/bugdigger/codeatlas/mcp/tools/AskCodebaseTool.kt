package com.bugdigger.codeatlas.mcp.tools

import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.rag.AnswerToken
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP tool: `ask_codebase`.
 *
 * Combines retrieval ([CodeAtlasIndexService.search][com.bugdigger.codeatlas.index.CodeAtlasIndexService.search])
 * with answer generation ([AnswerGenerator.generate][com.bugdigger.codeatlas.rag.AnswerGenerator.generate]).
 *
 * The Koog answer flow is collected end-to-end inside the tool handler; the buffered text plus a
 * JSON-encoded `sources` array are returned as two `TextContent` blocks. Streaming via MCP progress
 * notifications is intentionally not used in v1 — the buffered approach keeps the protocol path
 * simple and is correct independent of whether the host advertises progress support. (Hosts that
 * want token-by-token streaming can use the `search_code` tool plus their own LLM.)
 *
 * Cancellation: if the host disconnects mid-call, the SDK cancels the suspend handler, which
 * propagates through `flow.collect` into the upstream Koog `LLMClient` request. Nothing leaks.
 */
class AskCodebaseTool(
    private val backend: () -> Backend?,
) {

    /** Project-aware seam. Production impl in [com.bugdigger.codeatlas.mcp.tools.RealAskBackend]. */
    interface Backend {
        val isReady: Boolean
        val isProviderConfigured: Boolean

        /** Retrieve the top-K chunks that ground the answer. */
        suspend fun retrieveTopK(query: String, k: Int): List<CodeChunk>

        /** Stream the LLM answer for a query with the supplied chunks as context. */
        fun generate(query: String, chunks: List<CodeChunk>): Flow<AnswerToken>

        /** Convert chunks to wire DTOs (handles project-relative path normalization). */
        fun toSourceDtos(chunks: List<CodeChunk>): List<SourceDto>
    }

    suspend fun handle(arguments: JsonObject): ToolResult {
        val args = parseArgs(arguments) ?: return ToolResult.Failure(
            "ask_codebase: missing required string argument 'query'"
        )

        val b = backend() ?: return ToolResult.Failure(
            "No CodeAtlas-indexed project is open in this IDE."
        )
        if (!b.isReady) return ToolResult.Failure(
            "CodeAtlas index is not built yet. Wait for the initial build, or run Tools → CodeAtlas → Rebuild Index."
        )
        if (!b.isProviderConfigured) return ToolResult.Failure(
            "No LLM provider configured for CodeAtlas. Open Settings → CodeAtlas to set Anthropic, OpenAI, or Ollama."
        )

        val chunks = b.retrieveTopK(args.query, args.topK)
        val answerBuf = StringBuilder()
        var done: AnswerToken.Done? = null
        var error: AnswerToken.Error? = null

        try {
            b.generate(args.query, chunks).collect { token ->
                when (token) {
                    is AnswerToken.Delta -> answerBuf.append(token.text)
                    is AnswerToken.Done -> done = token
                    is AnswerToken.Error -> error = token
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return ToolResult.Failure(
                "ask_codebase: unexpected error from answer generator: ${t::class.simpleName}"
            )
        }

        error?.let { return ToolResult.Failure(it.message) }

        val sources = done?.sources ?: chunks
        val sourcesJson = JSON.encodeToString(
            ListSerializer(SourceDto.serializer()),
            b.toSourceDtos(sources),
        )

        // Two text blocks: full answer prose, then a JSON sources array.
        return ToolResult.Success(listOf(answerBuf.toString(), sourcesJson))
    }

    private fun parseArgs(arguments: JsonObject): Args? {
        val rawQuery = arguments[QUERY]?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
            ?: return null
        val topK = arguments[TOP_K]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
            ?.coerceIn(MIN_TOP_K, MAX_TOP_K) ?: DEFAULT_TOP_K
        return Args(rawQuery, topK)
    }

    private data class Args(val query: String, val topK: Int)

    companion object {
        const val NAME = "ask_codebase"
        const val DESCRIPTION =
            "Ask a natural-language question about the active project. Retrieves the most relevant " +
                "code with CodeAtlas's semantic index, then has the user-configured LLM provider " +
                "(Anthropic / OpenAI / Ollama) generate an answer that cites those sources. Returns " +
                "the answer text and a JSON-encoded list of source locations."

        const val QUERY = "query"
        const val TOP_K = "top_k"

        const val DEFAULT_TOP_K = 8
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 20

        internal val JSON = Json { encodeDefaults = false }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
