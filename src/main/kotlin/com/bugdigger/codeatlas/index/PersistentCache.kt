package com.bugdigger.codeatlas.index

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Single-file binary cache of extracted chunks and their embedding vectors.
 *
 * The header captures the embedding model id + dimension + schema version, so a
 * provider swap or a schema change silently invalidates the cache on next load.
 */
class PersistentCache(
    private val file: Path,
    private val expectedModelId: String,
    private val expectedDim: Int,
) {

    fun exists(): Boolean = Files.exists(file)

    fun save(chunks: List<CodeChunk>, vectors: List<FloatArray>) {
        require(chunks.size == vectors.size) {
            "chunks/vectors size mismatch: ${chunks.size} vs ${vectors.size}"
        }
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { os ->
            DataOutputStream(os).use { out ->
                out.writeInt(MAGIC)
                out.writeShort(SCHEMA_VERSION)
                out.writeUTF(expectedModelId)
                out.writeInt(expectedDim)
                out.writeInt(chunks.size)
                for (i in chunks.indices) {
                    writeChunk(out, chunks[i])
                    val v = vectors[i]
                    require(v.size == expectedDim)
                    for (f in v) out.writeFloat(f)
                }
            }
        }
    }

    /** Returns null on miss, magic/version/model/dim mismatch, or any corruption. */
    fun load(): LoadedIndex? {
        if (!exists()) return null
        return try {
            Files.newInputStream(file).use { ins ->
                DataInputStream(ins).use { input ->
                    if (input.readInt() != MAGIC) return null
                    if (input.readShort().toInt() != SCHEMA_VERSION) return null
                    if (input.readUTF() != expectedModelId) return null
                    if (input.readInt() != expectedDim) return null
                    val count = input.readInt()
                    val chunks = ArrayList<CodeChunk>(count)
                    val vectors = ArrayList<FloatArray>(count)
                    repeat(count) {
                        chunks += readChunk(input)
                        vectors += FloatArray(expectedDim) { input.readFloat() }
                    }
                    LoadedIndex(chunks, vectors)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeChunk(out: DataOutputStream, c: CodeChunk) {
        out.writeUTF(c.id)
        out.writeUTF(c.qualifiedName)
        out.writeUTF(c.kind.name)
        out.writeUTF(c.signature)
        writeNullable(out, c.docComment)
        out.writeUTF(c.language)
        out.writeUTF(c.virtualFileUrl)
        out.writeInt(c.startOffset)
        out.writeInt(c.endOffset)
        writeNullable(out, c.containerFqn)
        out.writeUTF(c.contentHash)
    }

    private fun readChunk(input: DataInputStream): CodeChunk = CodeChunk(
        id = input.readUTF(),
        qualifiedName = input.readUTF(),
        kind = ChunkKind.valueOf(input.readUTF()),
        signature = input.readUTF(),
        docComment = readNullable(input),
        language = input.readUTF(),
        virtualFileUrl = input.readUTF(),
        startOffset = input.readInt(),
        endOffset = input.readInt(),
        containerFqn = readNullable(input),
        contentHash = input.readUTF(),
    )

    private fun writeNullable(out: DataOutputStream, s: String?) {
        out.writeBoolean(s != null)
        if (s != null) out.writeUTF(s)
    }

    private fun readNullable(input: DataInputStream): String? =
        if (input.readBoolean()) input.readUTF() else null

    data class LoadedIndex(val chunks: List<CodeChunk>, val vectors: List<FloatArray>)

    companion object {
        private const val MAGIC = 0x434F4441 // "CODA"

        // Bumped to 2 alongside the embedder default flip from HashEmbeddingProvider to
        // OnnxEmbeddingProvider — old caches contain hash-projection vectors that are
        // incompatible with bge-small embeddings even though both are 384-dim, and the
        // (modelId, dim) check alone wouldn't flag a cache that was built locally with
        // a custom HashEmbeddingProvider(dim = 384).
        private const val SCHEMA_VERSION = 2
    }
}
