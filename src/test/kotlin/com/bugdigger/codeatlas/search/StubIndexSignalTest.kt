package com.bugdigger.codeatlas.search

import com.bugdigger.codeatlas.index.ChunkKind
import com.bugdigger.codeatlas.index.CodeChunk
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class StubIndexSignalTest : BasePlatformTestCase() {

    fun testBoostsCandidatesWhoseFqnMatchesIndexedClassOrMethod() {
        myFixture.configureByText(
            "AuthService.kt",
            """
            package demo
            class AuthService {
                fun login(user: String): Boolean = true
            }
            """.trimIndent(),
        )
        val candidates = listOf(
            chunk("demo.AuthService", ChunkKind.CLASS),
            chunk("demo.AuthService.login", ChunkKind.METHOD),
            chunk("demo.NotInIndex", ChunkKind.CLASS),
        )
        val boosts = StubIndexSignal(project).computeBoosts("AuthService login", candidates)
        assertEquals(setOf("t:demo.AuthService", "t:demo.AuthService.login"), boosts.keys)
        assertEquals(1f, boosts.getValue("t:demo.AuthService"), 0f)
    }

    fun testReturnsEmptyForUnknownTokens() {
        myFixture.configureByText("Empty.kt", "package demo")
        val boosts = StubIndexSignal(project).computeBoosts(
            "totallyUnknownIdentifier",
            listOf(chunk("demo.Foo", ChunkKind.CLASS)),
        )
        assertTrue(boosts.isEmpty())
    }

    fun testIgnoresShortTokens() {
        myFixture.configureByText(
            "X.kt",
            """
            package demo
            class X
            """.trimIndent(),
        )
        // Token "X" is below the 3-char minimum and won't be looked up.
        val boosts = StubIndexSignal(project).computeBoosts(
            "X",
            listOf(chunk("demo.X", ChunkKind.CLASS)),
        )
        assertTrue(boosts.isEmpty())
    }

    fun testEmptyCandidatesYieldsEmpty() {
        val boosts = StubIndexSignal(project).computeBoosts("anything", emptyList())
        assertTrue(boosts.isEmpty())
    }

    private fun chunk(fq: String, kind: ChunkKind): CodeChunk = CodeChunk(
        id = "t:$fq",
        qualifiedName = fq,
        kind = kind,
        signature = fq,
        docComment = null,
        language = "kotlin",
        virtualFileUrl = "file:///$fq.kt",
        startOffset = 0,
        endOffset = 1,
        containerFqn = null,
        contentHash = "h",
    )
}
