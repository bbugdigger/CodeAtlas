package com.bugdigger.codeatlas.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeChunkTest {

    @Test
    fun embeddingInputContainsQualifiedNameSignatureDocAndContainer() {
        val chunk = CodeChunk(
            id = "java:demo.Auth.login(String,String)",
            qualifiedName = "demo.Auth.login",
            kind = ChunkKind.METHOD,
            signature = "boolean login(String user, String pass)",
            docComment = "Authenticates the user.",
            language = "java",
            virtualFileUrl = "file://x/Auth.java",
            startOffset = 0,
            endOffset = 100,
            containerFqn = "demo.Auth",
            contentHash = "abc",
        )
        val input = chunk.embeddingInput()
        assertTrue(input.contains("demo.Auth.login"))
        assertTrue(input.contains("boolean login(String user, String pass)"))
        assertTrue(input.contains("Authenticates the user."))
        assertTrue(input.contains("demo.Auth"))
    }

    @Test
    fun embeddingInputOmitsBlankDocAndContainer() {
        val chunk = CodeChunk(
            id = "k:demo.topLevel",
            qualifiedName = "demo.topLevel",
            kind = ChunkKind.FUNCTION,
            signature = "fun topLevel(): Int",
            docComment = "   ",
            language = "kotlin",
            virtualFileUrl = "file://x/Top.kt",
            startOffset = 0,
            endOffset = 10,
            containerFqn = null,
            contentHash = "def",
        )
        val input = chunk.embeddingInput()
        assertEquals("demo.topLevel\nfun topLevel(): Int", input)
    }
}
