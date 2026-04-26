package com.bugdigger.codeatlas.mcp.tools

import com.bugdigger.codeatlas.index.CodeChunk
import com.bugdigger.codeatlas.search.RankedResult
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import kotlinx.serialization.Serializable
import java.nio.file.Paths

/**
 * MCP wire format for [CodeChunk]-derived results.
 *
 * Decoupled from [CodeChunk] on purpose: the wire format is a stable contract with external hosts,
 * while [CodeChunk] is free to evolve internally.
 */
@Serializable
data class SearchResultDto(
    val path: String,
    val qualifiedName: String,
    val kind: String,
    val signature: String,
    val language: String,
    val score: Float,
    val startOffset: Int,
    val endOffset: Int,
    val snippet: String? = null,
)

/** Trimmed shape used when emitting RAG sources alongside the answer; no snippet, no score. */
@Serializable
data class SourceDto(
    val path: String,
    val qualifiedName: String,
    val kind: String,
    val signature: String,
    val language: String,
    val startOffset: Int,
    val endOffset: Int,
)

internal const val MAX_SNIPPET_BYTES: Long = 256L * 1024

/**
 * Hard ceiling on the snippet length we'll embed in a tool result. Matches the spirit of
 * [com.bugdigger.codeatlas.rag.KoogAnswerGenerator.MAX_RESOLVE_BYTES] but lower since we may
 * concatenate many of these into one MCP response.
 */
internal const val MAX_SNIPPET_CHARS: Int = 4_000

/**
 * Map a [RankedResult] to a wire DTO, optionally inlining the source snippet.
 *
 * Snippet text is read from VFS under a [ReadAction]; if the file is gone, too large, or the
 * offsets fall outside the document, the snippet is dropped (the rest of the metadata is still
 * useful for the host).
 */
fun RankedResult.toDto(project: Project, includeSnippet: Boolean): SearchResultDto =
    SearchResultDto(
        path = chunk.relativePathFor(project),
        qualifiedName = chunk.qualifiedName,
        kind = chunk.kind.name,
        signature = chunk.signature,
        language = chunk.language,
        score = finalScore,
        startOffset = chunk.startOffset,
        endOffset = chunk.endOffset,
        snippet = if (includeSnippet) chunk.readSnippet() else null,
    )

fun CodeChunk.toSourceDto(project: Project): SourceDto = SourceDto(
    path = relativePathFor(project),
    qualifiedName = qualifiedName,
    kind = kind.name,
    signature = signature,
    language = language,
    startOffset = startOffset,
    endOffset = endOffset,
)

/**
 * Resolve [virtualFileUrl] to a path relative to [Project.getBasePath] when possible, or fall
 * back to the absolute filesystem path. Avoids leaking absolute paths outside the project root.
 */
internal fun CodeChunk.relativePathFor(project: Project): String {
    val absolute = absoluteFsPathOrNull() ?: return virtualFileUrl
    val base = project.basePath?.let { Paths.get(it) } ?: return absolute
    val abs = Paths.get(absolute)
    return runCatching { base.relativize(abs).toString().replace('\\', '/') }
        .getOrDefault(absolute)
}

internal fun CodeChunk.absoluteFsPathOrNull(): String? {
    // virtualFileUrl looks like "file:///C:/path/foo.kt" on Windows or "file:///path/foo.kt" elsewhere.
    val prefix = "file://"
    return if (virtualFileUrl.startsWith(prefix)) {
        val raw = virtualFileUrl.removePrefix(prefix)
        // On Windows the leading "/" before the drive letter has to be stripped to make a usable path.
        if (raw.length > 2 && raw[0] == '/' && raw[2] == ':') raw.substring(1) else raw
    } else null
}

internal fun CodeChunk.readSnippet(): String? {
    val vfm = VirtualFileManager.getInstance()
    return ReadAction.compute<String?, RuntimeException> {
        val file = vfm.findFileByUrl(virtualFileUrl) ?: return@compute null
        if (!file.isValid || file.length > MAX_SNIPPET_BYTES) return@compute null
        val text = file.contentsToByteArray().toString(Charsets.UTF_8)
        val start = startOffset.coerceIn(0, text.length)
        val end = endOffset.coerceIn(start, text.length)
        if (start == end) null else text.substring(start, end).take(MAX_SNIPPET_CHARS)
    }
}
