package com.bugdigger.codeatlas.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersistentCacheTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTripsChunksAndVectors() {
        val file = tmp.newFile("index.bin").toPath()
        val cache = PersistentCache(file, expectedModelId = "test-model", expectedDim = 3)

        val chunks = listOf(
            chunk("a", ChunkKind.CLASS, null, null),
            chunk("b", ChunkKind.METHOD, "docs here", "pkg.Owner"),
        )
        val vectors = listOf(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(-1f, 0f, 1f))
        cache.save(chunks, vectors)

        val loaded = cache.load()
        assertNotNull(loaded)
        assertEquals(chunks, loaded!!.chunks)
        assertEquals(vectors.size, loaded.vectors.size)
        for (i in vectors.indices) {
            assertEquals(vectors[i].toList(), loaded.vectors[i].toList())
        }
    }

    @Test
    fun modelIdMismatchInvalidatesCache() {
        val file = tmp.newFile("index.bin").toPath()
        PersistentCache(file, "model-v1", 2).save(
            listOf(chunk("a", ChunkKind.CLASS, null, null)),
            listOf(floatArrayOf(1f, 0f)),
        )
        val loaded = PersistentCache(file, "model-v2", 2).load()
        assertNull(loaded)
    }

    @Test
    fun dimMismatchInvalidatesCache() {
        val file = tmp.newFile("index.bin").toPath()
        PersistentCache(file, "m", 2).save(
            listOf(chunk("a", ChunkKind.CLASS, null, null)),
            listOf(floatArrayOf(1f, 0f)),
        )
        assertNull(PersistentCache(file, "m", 3).load())
    }

    @Test
    fun missingFileReturnsNull() {
        val file = tmp.root.toPath().resolve("does-not-exist.bin")
        assertNull(PersistentCache(file, "m", 2).load())
    }

    private fun chunk(name: String, kind: ChunkKind, doc: String?, container: String?): CodeChunk =
        CodeChunk(
            id = "t:$name",
            qualifiedName = name,
            kind = kind,
            signature = "fun $name()",
            docComment = doc,
            language = "kotlin",
            virtualFileUrl = "file:///$name.kt",
            startOffset = 0,
            endOffset = 1,
            containerFqn = container,
            contentHash = "h:$name",
        )
}
