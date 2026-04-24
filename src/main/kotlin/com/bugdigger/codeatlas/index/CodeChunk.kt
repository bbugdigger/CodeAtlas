package com.bugdigger.codeatlas.index

/**
 * A retrieval-sized unit of code. One PSI declaration (class, method, top-level function)
 * or a standalone documentation block produces exactly one CodeChunk.
 *
 * Vectors are keyed by [id]; invalidation uses [contentHash] to detect edits cheaply.
 */
data class CodeChunk(
    val id: String,
    val qualifiedName: String,
    val kind: ChunkKind,
    val signature: String,
    val docComment: String?,
    val language: String,
    val virtualFileUrl: String,
    val startOffset: Int,
    val endOffset: Int,
    val containerFqn: String?,
    val contentHash: String,
) {
    /** Text fed to the embedding model. Kept consistent across adapters so vectors are comparable. */
    fun embeddingInput(): String = buildString {
        append(qualifiedName)
        append('\n')
        append(signature)
        if (!docComment.isNullOrBlank()) {
            append('\n')
            append(docComment.trim())
        }
        if (!containerFqn.isNullOrBlank()) {
            append('\n')
            append("in ").append(containerFqn)
        }
    }
}

enum class ChunkKind {
    CLASS,
    INTERFACE,
    OBJECT,
    ENUM,
    ANNOTATION,
    METHOD,
    FUNCTION,
    CONSTRUCTOR,
    DOC,
}
