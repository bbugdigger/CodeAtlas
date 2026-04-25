package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringSignalsTest {

    @Test
    fun identifierMatchCountsHitsOverTokens() {
        val c = chunk("demo.AuthService.login", "fun login(user: String, pass: String): Boolean")
        assertEquals(1f, ScoringSignals.identifierMatch(c, "login auth"), 1e-6f)
        assertEquals(0.5f, ScoringSignals.identifierMatch(c, "login search"), 1e-6f)
        assertEquals(0f, ScoringSignals.identifierMatch(c, "payment checkout"), 1e-6f)
    }

    @Test
    fun shortQueryTokensAreIgnored() {
        val c = chunk("demo.Foo.bar", "fun bar()")
        // "is" and "in" are below the 3-char threshold and don't count.
        assertEquals(0f, ScoringSignals.identifierMatch(c, "is in on"), 1e-6f)
    }

    @Test
    fun kindFitBiasesKindsAgainstQuery() {
        val cls = chunk("demo.Foo", "class Foo", kind = ChunkKind.CLASS)
        val fn = chunk("demo.Foo.bar", "fun bar()", kind = ChunkKind.METHOD)
        assertTrue(ScoringSignals.kindFit(cls, "where is Foo") > ScoringSignals.kindFit(cls, "bar"))
        assertTrue(ScoringSignals.kindFit(fn, "how does bar handle things") >= 1f)
    }

    @Test
    fun fusedScoreMatchesWeightedSum() {
        val c = chunk(
            "demo.AuthService.login",
            "fun login(user: String): Boolean",
            kind = ChunkKind.METHOD,
            doc = "Authenticates the user.",
        )
        val fused = ScoringSignals.score(c, vectorScore = 0.5f, query = "how does login handle")
        val name = ScoringSignals.identifierMatch(c, "how does login handle")
        val kind = ScoringSignals.kindFit(c, "how does login handle")
        val expected = ScoringSignals.W_VECTOR * 0.5f +
            ScoringSignals.W_NAME * name +
            ScoringSignals.W_KIND * kind +
            ScoringSignals.W_DOC * 1f
        assertEquals(expected, fused, 1e-6f)
    }

    @Test
    fun stubBoostAddsExactlyWStubToTheFinalScore() {
        val c = chunk("demo.AuthService", "class AuthService", kind = ChunkKind.CLASS)
        val without = ScoringSignals.score(c, vectorScore = 0.5f, query = "AuthService", stubBoost = 0f)
        val with = ScoringSignals.score(c, vectorScore = 0.5f, query = "AuthService", stubBoost = 1f)
        assertEquals(ScoringSignals.W_STUB, with - without, 1e-6f)
    }

    private fun chunk(
        fq: String,
        sig: String,
        kind: ChunkKind = ChunkKind.METHOD,
        doc: String? = null,
    ): CodeChunk = CodeChunk(
        id = "t:$fq",
        qualifiedName = fq,
        kind = kind,
        signature = sig,
        docComment = doc,
        language = "kotlin",
        virtualFileUrl = "file:///x.kt",
        startOffset = 0,
        endOffset = 1,
        containerFqn = null,
        contentHash = "h",
    )
}
