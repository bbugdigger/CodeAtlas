package com.bugdigger.codeatlas.mcp.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP tool: `search_code`.
 *
 * Wraps [CodeAtlasIndexService.search][com.bugdigger.codeatlas.index.CodeAtlasIndexService.search]
 * for external hosts (Claude Desktop, Cursor, Claude Code CLI, ...). The handler is pure logic;
 * SDK glue lives in [com.bugdigger.codeatlas.mcp.McpServerService].
 *
 * Talks to the project via [Backend], so unit tests can substitute a deterministic implementation
 * without spinning up the IDE.
 */
class SearchCodeTool(
    private val backend: () -> Backend?,
) {

    /** Project-aware seam for the tool. Production impl in [com.bugdigger.codeatlas.mcp.tools.RealSearchBackend]. */
    interface Backend {
        /** True when the underlying index has at least one chunk and is ready to serve queries. */
        val isReady: Boolean

        /** Run a search and convert results to the wire DTO. The caller controls snippet inlining. */
        suspend fun search(query: String, limit: Int, includeSnippet: Boolean): List<SearchResultDto>
    }

    suspend fun handle(arguments: JsonObject): ToolResult {
        val args = parseArgs(arguments) ?: return ToolResult.Failure(
            "search_code: missing required string argument 'query'"
        )

        val b = backend() ?: return ToolResult.Failure(
            "No CodeAtlas-indexed project is open in this IDE. Open a project and wait for the initial index build to finish."
        )
        if (!b.isReady) return ToolResult.Failure(
            "CodeAtlas index is not built yet. Open the CodeAtlas tool window to monitor build progress, or run Tools → CodeAtlas → Rebuild Index."
        )

        val results = b.search(args.query, args.limit, args.includeSnippet)
        val json = JSON.encodeToString(ListSerializer(SearchResultDto.serializer()), results)
        return ToolResult.Success(json)
    }

    private fun parseArgs(arguments: JsonObject): Args? {
        val rawQuery = arguments[QUERY]?.let { it.jsonPrimitive.contentOrNullSafe() }?.takeIf { it.isNotBlank() }
            ?: return null
        val limit = arguments[LIMIT]?.let { runCatching { it.jsonPrimitive.int }.getOrNull() }
            ?.coerceIn(MIN_LIMIT, MAX_LIMIT) ?: DEFAULT_LIMIT
        val includeSnippet = arguments[INCLUDE_SNIPPET]?.let {
            runCatching { it.jsonPrimitive.boolean }.getOrNull()
        } ?: DEFAULT_INCLUDE_SNIPPET
        return Args(rawQuery, limit, includeSnippet)
    }

    private data class Args(val query: String, val limit: Int, val includeSnippet: Boolean)

    companion object {
        const val NAME = "search_code"
        const val DESCRIPTION =
            "Semantic search over the active project's CodeAtlas index. Returns ranked code " +
                "locations (file path, qualified name, signature) with optional source snippets. " +
                "Use this to find where a concept is implemented before asking follow-up questions."

        const val QUERY = "query"
        const val LIMIT = "limit"
        const val INCLUDE_SNIPPET = "include_snippet"

        const val DEFAULT_LIMIT = 10
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 50
        const val DEFAULT_INCLUDE_SNIPPET = true

        internal val JSON = Json { encodeDefaults = false }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()
