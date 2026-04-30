package com.bugdigger.codeatlas.mcp.tools

import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Production [SearchCodeTool.Backend] backed by [CodeAtlasIndexService].
 *
 * Created fresh per tool call (see [com.bugdigger.codeatlas.mcp.McpServerService]) so a project
 * close between calls is naturally observed — the resolver returns null and the tool short-circuits.
 */
fun searchBackendFor(project: Project): SearchCodeTool.Backend = object : SearchCodeTool.Backend {
    private val service: CodeAtlasIndexService = project.service()

    override val isReady: Boolean
        get() = service.chunkCount > 0

    override suspend fun search(query: String, limit: Int, includeSnippet: Boolean): List<SearchResultDto> =
        service.search(query, limit).map { it.toDto(project, includeSnippet) }
}
