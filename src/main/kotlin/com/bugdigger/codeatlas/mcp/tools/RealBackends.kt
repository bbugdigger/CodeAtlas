package com.bugdigger.codeatlas.mcp.tools

import com.bugdigger.codeatlas.index.CodeAtlasIndexService
import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.rag.AnswerToken
import com.bugdigger.codeatlas.rag.KoogAnswerGenerator
import com.bugdigger.codeatlas.settings.CodeAtlasSettingsService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.Flow

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

/**
 * Production [AskCodebaseTool.Backend] backed by [CodeAtlasIndexService] for retrieval and
 * [KoogAnswerGenerator] for generation.
 *
 * The [KoogAnswerGenerator]'s providerSupplier is invoked lazily inside the answer flow, so the
 * API key is held in memory only for the duration of one call (mirrors the existing in-IDE Ask
 * flow).
 */
fun askBackendFor(project: Project): AskCodebaseTool.Backend = object : AskCodebaseTool.Backend {
    private val indexService: CodeAtlasIndexService = project.service()
    private val settings: CodeAtlasSettingsService = project.service()

    override val isReady: Boolean
        get() = indexService.chunkCount > 0

    override val isProviderConfigured: Boolean
        get() = settings.resolveProvider() != null

    override suspend fun retrieveTopK(query: String, k: Int): List<CodeChunk> =
        indexService.search(query, k).map { it.chunk }

    override fun generate(query: String, chunks: List<CodeChunk>): Flow<AnswerToken> {
        val generator = KoogAnswerGenerator(providerSupplier = {
            settings.resolveProvider()
                ?: error("LLM provider became unset after isProviderConfigured check")
        })
        return generator.generate(query, chunks)
    }

    override fun toSourceDtos(chunks: List<CodeChunk>): List<SourceDto> =
        chunks.map { it.toSourceDto(project) }
}
