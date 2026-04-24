package com.bugdigger.codeatlas.language

import com.bugdigger.codeatlas.index.ChunkKind
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class KotlinLanguageAdapterTest : BasePlatformTestCase() {

    fun testExtractsClassMethodAndTopLevelFunction() {
        val file = myFixture.configureByText(
            "Auth.kt",
            """
            package demo

            /** Handles authentication. */
            class AuthService {
                /** Log in with credentials. */
                fun login(user: String, pass: String): Boolean { return true }
                fun logout() {}
            }

            /** Top-level helper. */
            fun topLevel(): Int = 42
            """.trimIndent(),
        )

        val chunks = KotlinLanguageAdapter().extract(file)

        val kinds = chunks.map { it.kind }.toSet()
        assertTrue("expected CLASS kind, got $kinds", kinds.contains(ChunkKind.CLASS))
        assertTrue("expected METHOD kind, got $kinds", kinds.contains(ChunkKind.METHOD))
        assertTrue("expected FUNCTION kind, got $kinds", kinds.contains(ChunkKind.FUNCTION))

        val classChunk = chunks.single { it.qualifiedName == "demo.AuthService" }
        assertEquals(ChunkKind.CLASS, classChunk.kind)
        assertNotNull("AuthService should carry its KDoc", classChunk.docComment)

        val loginChunk = chunks.single { it.qualifiedName == "demo.AuthService.login" }
        assertEquals(ChunkKind.METHOD, loginChunk.kind)
        assertTrue(loginChunk.signature.contains("login"))
        assertTrue(loginChunk.signature.contains("Boolean"))
        assertEquals("demo.AuthService", loginChunk.containerFqn)

        val topChunk = chunks.single { it.qualifiedName == "demo.topLevel" }
        assertEquals(ChunkKind.FUNCTION, topChunk.kind)
        assertEquals("demo", topChunk.containerFqn)
    }

    fun testInterfaceAndObjectKinds() {
        val file = myFixture.configureByText(
            "Types.kt",
            """
            package demo
            interface Repo { fun find(id: String): String? }
            object Registry { fun register() {} }
            """.trimIndent(),
        )
        val chunks = KotlinLanguageAdapter().extract(file)
        assertEquals(ChunkKind.INTERFACE, chunks.single { it.qualifiedName == "demo.Repo" }.kind)
        assertEquals(ChunkKind.OBJECT, chunks.single { it.qualifiedName == "demo.Registry" }.kind)
    }
}
