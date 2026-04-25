package com.bugdigger.codeatlas.rag

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Regression tests for [KoogAnswerGenerator.resolveSources] safety.
 *
 * Pre-fix, `contentsToByteArray()` was called on every backing file regardless
 * of size. A multi-megabyte generated source could OOM the IDE. The fix caps
 * file length at [KoogAnswerGenerator.MAX_RESOLVE_BYTES] (overridable via the
 * constructor for testing) and falls back to the chunk signature when the
 * cap is exceeded.
 */
class KoogAnswerGeneratorTest : BasePlatformTestCase() {

    fun testResolvesContentWhenFileWithinCap() {
        val text = "class Foo { fun bar() = 1 }"
        val psi = myFixture.configureByText("Foo.kt", text)
        val vfile = psi.virtualFile

        val chunk = chunk(vfile.url, start = 0, end = vfile.length.toInt(), signature = "class Foo")
        val generator = KoogAnswerGenerator(
            providerSupplier = { error("provider not used") },
            maxResolveBytes = 1024,
        )

        val resolved = generator.resolveSources(listOf(chunk))

        assertEquals(1, resolved.size)
        assertTrue(
            "expected source text to start with the file content, got '${resolved[0].sourceText}'",
            resolved[0].sourceText.startsWith("class Foo"),
        )
    }

    fun testFallsBackToSignatureWhenFileExceedsCap() {
        val text = "class Foo { fun bar() = 1 }"
        val psi = myFixture.configureByText("Foo.kt", text)
        val vfile = psi.virtualFile

        val chunk = chunk(vfile.url, start = 0, end = vfile.length.toInt(), signature = "class Foo")
        // Force the cap to be smaller than the fixture file's length so the read path is skipped.
        val tinyCap = 5L
        assertTrue("test precondition: file should exceed the cap", vfile.length > tinyCap)
        val generator = KoogAnswerGenerator(
            providerSupplier = { error("provider not used") },
            maxResolveBytes = tinyCap,
        )

        val resolved = generator.resolveSources(listOf(chunk))

        assertEquals(1, resolved.size)
        assertEquals("class Foo", resolved[0].sourceText)
    }

    fun testFallsBackToSignatureWhenFileMissing() {
        val chunk = chunk(
            virtualFileUrl = "file:///does/not/exist.kt",
            start = 0,
            end = 10,
            signature = "fun missing()",
        )
        val generator = KoogAnswerGenerator(
            providerSupplier = { error("provider not used") },
            maxResolveBytes = 1024,
        )

        val resolved = generator.resolveSources(listOf(chunk))

        assertEquals(1, resolved.size)
        assertEquals("fun missing()", resolved[0].sourceText)
    }

    private fun chunk(virtualFileUrl: String, start: Int, end: Int, signature: String): CodeChunk =
        CodeChunk(
            id = "t:$virtualFileUrl",
            qualifiedName = "demo.Foo",
            kind = ChunkKind.CLASS,
            signature = signature,
            docComment = null,
            language = "kotlin",
            virtualFileUrl = virtualFileUrl,
            startOffset = start,
            endOffset = end,
            containerFqn = null,
            contentHash = "h",
        )
}
