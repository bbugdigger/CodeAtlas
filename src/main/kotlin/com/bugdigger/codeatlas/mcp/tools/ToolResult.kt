package com.bugdigger.codeatlas.mcp.tools

/**
 * SDK-agnostic tool response. The MCP-SDK glue in [com.bugdigger.codeatlas.mcp.McpServerService]
 * translates these to `CallToolResult` instances; keeping tool logic free of the SDK types makes
 * unit tests trivial (no SDK dependency, no in-process HTTP fixture).
 *
 * [Success.textBlocks] is rendered as one or more `TextContent` blocks in order. [Failure.message]
 * becomes a `CallToolResult(isError = true, content = [TextContent(message)])`. All messages are
 * expected to be safe to surface verbatim — redaction is the responsibility of whoever produces
 * the message (e.g. [com.bugdigger.codeatlas.rag.KoogAnswerGenerator] for LLM errors).
 */
sealed class ToolResult {
    data class Success(val textBlocks: List<String>) : ToolResult() {
        constructor(text: String) : this(listOf(text))
    }

    data class Failure(val message: String) : ToolResult()
}
